(ns editor.script-api
  (:require [dynamo.graph :as g]
            [editor.code.data :as data]
            [editor.code.resource :as r]
            [editor.code.script-intelligence :as si]
            [editor.defold-project :as project]
            [editor.resource :as resource]
            [editor.workspace :as workspace]
            [editor.yamlparser :as yp]
            [internal.graph.error-values :as error-values])
  (:import [org.snakeyaml.engine.v1.exceptions Mark MarkedYamlEngineException]))

(set! *warn-on-reflection* true)
(set! *unchecked-math* :warn-on-boxed)

(defmulti convert
  "Converts YAML documentation input to the internal auto-complete format defined
  in `editor.lua` namespace."
  #(or (:type %) :no-type))

(defn- name-with-ns
  [ns name]
  (if (nil? ns)
    name
    (str ns \. name)))

(defmethod convert "table"
  [{:keys [ns name members desc]}]
  (let [name (name-with-ns ns name)]
    (into [{:type :namespace
            :name name
            :doc desc
            :display-string name
            :insert-string name}]
          (map (comp convert #(assoc % :ns name))
               (filter map? members)))))

(defn- param-names
  [params remove-optional?]
  (letfn [(bracketname? [x] (= \[ (first (:name x))))
          (optional? [x] (or (:optional x) (bracketname? x)))]
    (mapv :name
          (if remove-optional?
            (filterv #(not (optional? %)) params)
            (mapv #(if (and (:optional %) (not (bracketname? %)))
                     (assoc % :name (str "[" (:name %) "]"))
                     %)
                  params)))))

(defn- build-param-string
  ([params]
   (build-param-string params false))
  ([params remove-optional?]
   (str "(" (apply str (interpose ", " (param-names params remove-optional?))) ")")))

(defmethod convert "function"
  [{:keys [ns name desc parameters]}]
  (let [name (name-with-ns ns name)]
    {:type :function
     :name name
     :doc desc
     :display-string (str name (build-param-string parameters))
     :insert-string (str name (build-param-string parameters true))
     :tab-triggers {:select (param-names parameters true) :exit (when parameters ")")}}))

(defmethod convert :default
  [{:keys [ns name desc]}]
  (let [name (name-with-ns ns name)]
    {:type :variable
     :name name
     :doc desc
     :display-string name
     :insert-string name}))

(defmethod convert :no-type
  [x]
  nil)

(defn convert-lines
  [lines]
  (mapv convert (yp/load (data/lines-reader lines) keyword)))

(defn combine-conversions
  "This function combines the individual hierarchical conversions into a map where
  all the namespaces are keys at the top level mapping to a vector of their
  respective contents. A global namespace is also added with the empty string as
  a name, which contains a vector of namespace entries to enable auto completion
  of namespace names."
  [conversions]
  (first (reduce
           (fn [[m ns] x]
             (cond
               (vector? x)
               ;; Recurse into sublevels and merge the current map with the
               ;; result. Any key collisions will have vector values so we
               ;; can merge them with into.
               [(merge-with into m (combine-conversions x)) ns]

               :else
               (if (= :namespace (:type x))
                 [(let [m (assoc m (:name x) [])
                        m (if-not (= "" ns)
                            (update m ns conj x)
                            m)]
                    ;; Always add namespaces as members of the global namespace.
                    (update m "" conj x))
                  (:name x)]
                 (if x
                   [(update m ns conj x) ns]
                   ;; Don't add empty parse results. They are probably
                   ;; from syntactically valid but incomplete yaml
                   ;; records.
                   [m ns]))))
           [{"" []} ""]
           conversions)))

(defn lines->completion-info
  [lines]
  (combine-conversions (remove nil? (convert-lines lines))))

(g/defnk produce-completions
  [parse-result]
  (g/precluding-errors parse-result parse-result))

(g/defnk produce-parse-result
  [_node-id lines]
  (try
    (lines->completion-info lines)
    (catch MarkedYamlEngineException myee
      (let [mark ^Mark (.get (.getProblemMark myee))
            line (inc (.getLine mark))
            row (.getLine mark)
            col (.getColumn mark)
            ev (-> (error-values/error-fatal (.getMessage myee))
                   (assoc :row row :line line :col col))]
        (g/package-errors _node-id ev)))))

(g/defnk produce-build-errors
  [parse-result]
  (when (g/error-package? parse-result)
    (g/unpack-errors parse-result)))

(g/defnode ScriptApiNode
  (inherits r/CodeEditorResourceNode)
  (output parse-result g/Any :cached produce-parse-result)
  (output build-errors g/Any produce-build-errors)
  (output completions si/ScriptCompletions produce-completions))

(defn- load-script-api
  [project self resource]
  (let [si (project/script-intelligence project)]
    (concat (g/connect self :completions si :lua-completions)
            (g/connect self :build-errors si :build-errors)
            (when (resource/file-resource? resource)
              (g/connect self :save-data project :save-data))
            ((var-get #'r/eager-load) self resource))))

(defn register-resource-types
  [workspace]
  (workspace/register-resource-type workspace
                                    :ext "script_api"
                                    :label "Script API"
                                    :icon "icons/32/Icons_29-AT-Unknown.png"
                                    :view-types [:code :default]
                                    :view-opts nil
                                    :node-type ScriptApiNode
                                    :load-fn load-script-api
                                    :read-fn r/read-fn
                                    :write-fn r/write-fn
                                    :textual? true
                                    :auto-connect-save-data? false))

