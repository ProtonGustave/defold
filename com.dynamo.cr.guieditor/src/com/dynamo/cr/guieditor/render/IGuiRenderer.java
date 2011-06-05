package com.dynamo.cr.guieditor.render;

import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.geom.Rectangle2D;

import javax.media.opengl.GL;

import com.dynamo.gui.proto.Gui.NodeDesc.BlendMode;
import com.sun.opengl.util.j2d.TextRenderer;
import com.sun.opengl.util.texture.Texture;

public interface IGuiRenderer {

    public void begin(GL gl);

    public void end();

    public void drawQuad(double x0, double y0, double x1, double y1, double r,
            double g, double b, double a, BlendMode blendMode, Texture texture);

    public void drawString(TextRenderer textRenderer, String text, double x0, double y0,
            double r, double g, double b, double a, BlendMode blendMode,
            Texture texture);

    public void drawStringBounds(TextRenderer textRenderer, String text, double x0,
            double y0, double r, double g, double b, double a);

    public Rectangle2D getStringBounds(TextRenderer textRenderer, String text);

    public void setName(int name);

    public void clearName();

    public FontMetrics getFontMetrics(Font font);

    /**
     * Start GL select
     * @note x and y are specify center of selection
     * @param gl GL context
     * @param x center x coordinate
     * @param y center y coordinate
     * @param w selection width
     * @param h selection height
     * @param viewPort view-port
     */
    public void beginSelect(GL gl, int x, int y, int w, int h, int viewPort[]);

    public SelectResult endSelect();

    public TextRenderer getDebugTextRenderer();

}