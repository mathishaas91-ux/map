package dev.mapinslot.paper;

import org.bukkit.entity.Player;
import org.bukkit.map.MapCanvas;
import org.bukkit.map.MapCursorCollection;
import org.bukkit.map.MapFont;
import org.bukkit.map.MapPalette;
import org.bukkit.map.MapRenderer;
import org.bukkit.map.MapView;

import java.awt.Color;
import java.awt.Image;
import java.awt.image.BufferedImage;

/**
 * Faengt die Pixel ein, die die Renderer einer Karte zeichnen,
 * und wandelt sie in ein BufferedImage um.
 */
@SuppressWarnings("deprecation")
final class MapCapture implements MapCanvas {

    private static final int SIZE = 128;

    private final MapView view;
    private final byte[] pixels = new byte[SIZE * SIZE];
    private MapCursorCollection cursors = new MapCursorCollection();

    private MapCapture(MapView view) {
        this.view = view;
    }

    static BufferedImage render(MapView view, Player player, Color background) {
        MapCapture canvas = new MapCapture(view);
        for (MapRenderer renderer : view.getRenderers()) {
            renderer.render(view, canvas, player);
        }
        BufferedImage img = new BufferedImage(SIZE, SIZE, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < SIZE; y++) {
            for (int x = 0; x < SIZE; x++) {
                byte b = canvas.pixels[y * SIZE + x];
                Color c = background;
                if (b != 0) {
                    try {
                        Color pc = MapPalette.getColor(b);
                        if (pc != null && pc.getAlpha() > 0) {
                            c = pc;
                        }
                    } catch (RuntimeException ignored) {
                        // ungueltiger Farbindex -> Hintergrund
                    }
                }
                img.setRGB(x, y, 0xFF000000 | (c.getRGB() & 0xFFFFFF));
            }
        }
        return img;
    }

    private static boolean inBounds(int x, int y) {
        return x >= 0 && y >= 0 && x < SIZE && y < SIZE;
    }

    @Override
    public MapView getMapView() {
        return view;
    }

    @Override
    public MapCursorCollection getCursors() {
        return cursors;
    }

    @Override
    public void setCursors(MapCursorCollection cursors) {
        this.cursors = cursors;
    }

    @Override
    public void setPixel(int x, int y, byte color) {
        if (inBounds(x, y)) pixels[y * SIZE + x] = color;
    }

    @Override
    public byte getPixel(int x, int y) {
        return inBounds(x, y) ? pixels[y * SIZE + x] : 0;
    }

    @Override
    public byte getBasePixel(int x, int y) {
        return getPixel(x, y);
    }

    @Override
    public void setPixelColor(int x, int y, Color color) {
        if (!inBounds(x, y)) return;
        pixels[y * SIZE + x] = color == null ? 0 : MapPalette.matchColor(color);
    }

    @Override
    public Color getPixelColor(int x, int y) {
        byte b = getPixel(x, y);
        if (b == 0) return null;
        try {
            return MapPalette.getColor(b);
        } catch (RuntimeException e) {
            return null;
        }
    }

    @Override
    public Color getBasePixelColor(int x, int y) {
        return getPixelColor(x, y);
    }

    @Override
    public void drawImage(int x, int y, Image image) {
        int w = image.getWidth(null);
        int h = image.getHeight(null);
        if (w <= 0 || h <= 0) return;
        byte[] bytes = MapPalette.imageToBytes(image);
        for (int iy = 0; iy < h; iy++) {
            for (int ix = 0; ix < w; ix++) {
                setPixel(x + ix, y + iy, bytes[iy * w + ix]);
            }
        }
    }

    @Override
    public void drawText(int x, int y, MapFont font, String text) {
        // Text wird fuer die Mini-Vorschau bewusst nicht gezeichnet.
    }
}
