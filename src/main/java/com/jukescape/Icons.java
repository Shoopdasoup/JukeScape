package com.jukescape;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Polygon;
import java.awt.RenderingHints;
import java.awt.geom.Ellipse2D;
import java.awt.image.BufferedImage;
import java.util.function.Consumer;
import javax.swing.ImageIcon;
import net.runelite.client.ui.ColorScheme;

/**
 * Small icons, drawn rather than shipped as image files.
 */
final class Icons
{
	private static final int SIZE = 10;

	private Icons()
	{
	}

	/**
	 * A music note for the sidebar button, in case the game's music tab sprite can't be loaded.
	 */
	static BufferedImage navigation()
	{
		return draw(16, new Color(0xE0E0E0), g ->
		{
			g.fill(new Ellipse2D.Float(2.5f, 10.5f, 6.5f, 4.5f));
			g.fillRect(7, 2, 2, 11);
			g.fill(new Polygon(new int[]{9, 14, 14, 9}, new int[]{2, 4, 7, 5}, 4));
		});
	}

	static ImageIcon up(boolean hover)
	{
		return new ImageIcon(draw(SIZE, color(hover), g ->
			g.fill(new Polygon(new int[]{1, 5, 9}, new int[]{7, 2, 7}, 3))));
	}

	static ImageIcon down(boolean hover)
	{
		return new ImageIcon(draw(SIZE, color(hover), g ->
			g.fill(new Polygon(new int[]{1, 5, 9}, new int[]{3, 8, 3}, 3))));
	}

	static ImageIcon remove(boolean hover)
	{
		return new ImageIcon(draw(SIZE, hover ? ColorScheme.PROGRESS_ERROR_COLOR : ColorScheme.LIGHT_GRAY_COLOR, g ->
		{
			g.setStroke(new BasicStroke(1.6f));
			g.drawLine(2, 2, 8, 8);
			g.drawLine(8, 2, 2, 8);
		}));
	}

	static ImageIcon skip(boolean hover)
	{
		return new ImageIcon(draw(14, color(hover), g ->
		{
			g.fill(new Polygon(new int[]{2, 9, 2}, new int[]{2, 7, 12}, 3));
			g.fillRect(10, 2, 2, 10);
		}));
	}

	private static Color color(boolean hover)
	{
		return hover ? ColorScheme.BRAND_ORANGE : ColorScheme.LIGHT_GRAY_COLOR;
	}

	private static BufferedImage draw(int size, Color color, Consumer<Graphics2D> painter)
	{
		BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = image.createGraphics();
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		g.setColor(color);
		painter.accept(g);
		g.dispose();
		return image;
	}
}
