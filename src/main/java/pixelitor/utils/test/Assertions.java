/*
 * Copyright 2018 Laszlo Balazs-Csiki and Contributors
 *
 * This file is part of Pixelitor. Pixelitor is free software: you
 * can redistribute it and/or modify it under the terms of the GNU
 * General Public License, version 3 as published by the Free
 * Software Foundation.
 *
 * Pixelitor is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with Pixelitor. If not, see <http://www.gnu.org/licenses/>.
 */

package pixelitor.utils.test;

import pixelitor.Composition;
import pixelitor.gui.ImageComponent;
import pixelitor.gui.ImageComponents;
import pixelitor.layers.Layer;
import pixelitor.layers.LayerMask;
import pixelitor.tools.Tool;
import pixelitor.tools.Tools;

import java.awt.Color;
import java.awt.Rectangle;
import java.awt.Shape;
import java.awt.image.BufferedImage;
import java.awt.image.WritableRaster;

/**
 * Static, boolean-returning methods that
 * can be conveniently used after the assert keyword
 */
public class Assertions {
    private Assertions() {
    }

    @SuppressWarnings("SameReturnValue")
    public static boolean selectionBoundsAre(int x, int y, int w, int h) {
        Composition comp = ImageComponents.getActiveCompOrNull();
        if (comp == null) {
            throw new IllegalStateException();
        }
        if (!comp.hasSelection()) {
            throw new IllegalStateException();
        }
        Shape shape = comp.getSelectionShape();
        Rectangle expected = new Rectangle(x, y, w, h);
        Rectangle actual = shape.getBounds();
        if (actual.equals(expected)) {
            return true;
        } else {
            throw new AssertionError("expected = " + expected + ", actual = " + actual);
        }
    }

    public static boolean hasMask(boolean enabled, boolean linked) {
        Layer layer = ImageComponents.getActiveLayerOrNull();
        if (layer == null) {
            throw new IllegalStateException();
        }
        if (!layer.hasMask()) {
            return false;
        }
        if (layer.isMaskEnabled() != enabled) {
            return false;
        }
        LayerMask mask = layer.getMask();
        return mask.isLinked() == linked;
    }


    public static boolean cropToolRectangleBoundsAre(int x, int y, int w, int h) {
        ImageComponent ic = ImageComponents.getActiveIC();
        if (ic == null) {
            throw new IllegalStateException();
        }
        return Tools.CROP.getCropRect().getCo().equals(new Rectangle(x, y, w, h));
    }

    public static boolean selectedToolIs(Tool expected) {
        return Tools.getCurrent() == expected;
    }

    public static boolean pixelColorIs(int x, int y, int a, int r, int g, int b) {
        Composition comp = ImageComponents.getActiveCompOrNull();
        if (comp == null) {
            throw new IllegalStateException();
        }
        BufferedImage img = comp.getCompositeImage();
        int rgb = img.getRGB(x, y);
        Color c = new Color(rgb);
        return c.getAlpha() == a && c.getRed() == r && c.getGreen() == g && c.getBlue() == b;
    }

    public static boolean numLayersIs(int expected) {
        Composition comp = ImageComponents.getActiveCompOrNull();
        if (comp == null) {
            throw new IllegalStateException();
        }
        return comp.getNumLayers() == expected;
    }

    public static boolean callingClassIs(String name) {
        // it checks the caller of the caller
        String callingClassName = new Exception().getStackTrace()[2].getClassName();
        return callingClassName.contains(name);
    }

    @SuppressWarnings("SameReturnValue")
    public static boolean checkRasterMinimum(BufferedImage newImage) {
        if (RandomGUITest.isRunning()) {
            WritableRaster raster = newImage.getRaster();
            if ((raster.getMinX() != 0) || (raster.getMinY() != 0)) {
                throw new
                        IllegalArgumentException("Raster " + raster +
                        " has minX or minY not equal to zero: "
                        + raster.getMinX() + ' ' + raster.getMinY());
            }
        }
        return true;
    }
}
