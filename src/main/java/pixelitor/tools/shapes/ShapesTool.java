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

package pixelitor.tools.shapes;

import org.jdesktop.swingx.combobox.EnumComboBoxModel;
import pixelitor.Canvas;
import pixelitor.Composition;
import pixelitor.filters.gui.EffectsParam;
import pixelitor.filters.gui.StrokeParam;
import pixelitor.filters.painters.AreaEffects;
import pixelitor.gui.ImageComponent;
import pixelitor.gui.ImageComponents;
import pixelitor.gui.utils.GUIUtils;
import pixelitor.history.History;
import pixelitor.history.NewSelectionEdit;
import pixelitor.history.PixelitorEdit;
import pixelitor.history.SelectionChangeEdit;
import pixelitor.layers.Drawable;
import pixelitor.selection.Selection;
import pixelitor.tools.ClipStrategy;
import pixelitor.tools.DragTool;
import pixelitor.tools.shapes.history.CreateBoxedShapeEdit;
import pixelitor.tools.transform.TransformBox;
import pixelitor.tools.util.DragDisplayType;
import pixelitor.tools.util.ImDrag;
import pixelitor.tools.util.PMouseEvent;
import pixelitor.utils.Cursors;
import pixelitor.utils.Lazy;
import pixelitor.utils.debug.DebugNode;

import javax.swing.*;
import java.awt.BasicStroke;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.Shape;
import java.awt.Stroke;
import java.awt.event.MouseEvent;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;

import static pixelitor.Composition.ImageChangeActions.REPAINT;
import static pixelitor.tools.shapes.ShapesToolState.INITIAL_DRAG;
import static pixelitor.tools.shapes.ShapesToolState.NO_INTERACTION;
import static pixelitor.tools.shapes.ShapesToolState.TRANSFORM;

/**
 * The Shapes Tool
 */
public class ShapesTool extends DragTool {
    private final EnumComboBoxModel<ShapeType> typeModel
            = new EnumComboBoxModel<>(ShapeType.class);
    private final EnumComboBoxModel<ShapesAction> actionModel
            = new EnumComboBoxModel<>(ShapesAction.class);
    private final EnumComboBoxModel<TwoPointBasedPaint> fillPaintModel
            = new EnumComboBoxModel<>(TwoPointBasedPaint.class);
    private final EnumComboBoxModel<TwoPointBasedPaint> strokePaintModel
            = new EnumComboBoxModel<>(TwoPointBasedPaint.class);

    private final JComboBox<TwoPointBasedPaint> fillPaintCombo
            = new JComboBox<>(fillPaintModel);
    private final JComboBox<TwoPointBasedPaint> strokePaintCombo
            = new JComboBox<>(strokePaintModel);

    private final StrokeParam strokeParam = new StrokeParam("");

    // During a single mouse drag, only one stroke should be created
    // This is particularly important for "random shape"
    private final Lazy<Stroke> stroke = Lazy.of(strokeParam::createStroke);

    private StyledShape styledShape;

    private JButton strokeSettingsButton;
    private JDialog strokeSettingsDialog;

    private JButton effectsButton;
    private JDialog effectsDialog;
    private final EffectsParam effectsParam = new EffectsParam("");

    private Shape backupSelectionShape = null;

    public static final BasicStroke STROKE_FOR_OPEN_SHAPES = new BasicStroke(1);

    private TransformBox transformBox;

    private ShapesToolState state = NO_INTERACTION;

    public ShapesTool() {
        super("Shapes", 'u', "shapes_tool_icon.png",
                "<b>drag</b> to draw a shape. " +
                        "Hold <b>Alt</b> down to drag from the center. " +
                        "Hold <b>SPACE</b> down while drawing to move the shape. ",
                Cursors.DEFAULT, true, true,
                false, ClipStrategy.FULL);

        strokePaintModel.setSelectedItem(TwoPointBasedPaint.BACKGROUND);

        spaceDragStartPoint = true;

        strokeParam.setAdjustmentListener(() -> regenerateShape(strokeParam));
        effectsParam.setAdjustmentListener(() -> regenerateShape(effectsParam));
    }

    @Override
    public void initSettingsPanel() {
        JComboBox<ShapeType> shapeTypeCB = new JComboBox<>(typeModel);
        settingsPanel.addWithLabel("Shape:", shapeTypeCB, "shapeTypeCB");
        // make sure all values are visible without a scrollbar
        shapeTypeCB.setMaximumRowCount(typeModel.getSize());
        shapeTypeCB.addActionListener(e -> regenerateShape(typeModel));

        JComboBox<ShapesAction> actionCB = new JComboBox<>(actionModel);
        settingsPanel.addWithLabel("Action:", actionCB, "actionCB");
        actionCB.addActionListener(e -> updateEnabledState());
        actionCB.addActionListener(e -> regenerateShape(actionModel));

        settingsPanel.addWithLabel("Fill:", fillPaintCombo);
        fillPaintCombo.addActionListener(e -> regenerateShape(fillPaintModel));

        settingsPanel.addWithLabel("Stroke:", strokePaintCombo);
        strokePaintCombo.addActionListener(e -> regenerateShape(strokePaintModel));

        strokeSettingsButton = settingsPanel.addButton("Stroke Settings...",
                e -> initAndShowStrokeSettingsDialog());

        effectsButton = settingsPanel.addButton("Effects...",
                e -> showEffectsDialog());

        updateEnabledState();
    }

    private void showEffectsDialog() {

//        if (effectsPanel == null) {
//            effectsPanel = new EffectsPanel(
//                    () -> effectsPanel.updateEffectsFromGUI(), null);
//        }

        effectsDialog = effectsParam.buildDialog(null, false);
        GUIUtils.showDialog(effectsDialog);
    }

    @Override
    public void dragStarted(PMouseEvent e) {
        if (state == TRANSFORM) {
            assert transformBox != null;
            assert styledShape != null;
            if (transformBox.handleMousePressed(e)) {
                return;
            }
            // if pressed outside the transform box,
            // finish the existing shape
            finalizeShape(e.getComp());
        }

        // if this method didn't return yet, start a new shape
        state = INITIAL_DRAG;
        styledShape = new StyledShape(getSelectedType(), getSelectedAction(), this);
        backupSelectionShape = e.getComp().getSelectionShape();
    }

    @Override
    public void ongoingDrag(PMouseEvent e) {
        if (state == TRANSFORM) {
            assert transformBox != null;
            assert styledShape != null;
            if (transformBox.handleMouseDragged(e)) {
                return;
            }
            throw new IllegalStateException("should not get here");
        }


        assert state == INITIAL_DRAG;
        assert styledShape != null;

        userDrag.setStartFromCenter(e.isAltDown());


        styledShape.setImDrag(userDrag.toImDrag());

        Composition comp = e.getComp();

        // this will trigger paintOverLayer, therefore the continuous drawing of the shape
        // TODO it could be optimized not to repaint the whole image, however
        // it is not easy as some shapes extend beyond their drag rectangle
        comp.imageChanged(REPAINT);
    }

    @Override
    public void dragFinished(PMouseEvent e) {
        if (state == TRANSFORM) {
            assert transformBox != null;
            assert styledShape != null;
            if (transformBox.handleMouseReleased(e)) {
                return;
            }
            throw new IllegalStateException("should not get here");
        }

        assert state == INITIAL_DRAG;

        if (userDrag.isClick()) {
            // cancel the shape started in dragStarted
            styledShape = null;
            return;
        }

        userDrag.setStartFromCenter(e.isAltDown());
        Composition comp = e.getComp();


        ShapesAction action = getSelectedAction();
        if (!action.createSelection()) {
//            finalizeShape(comp, action);

            transformBox = styledShape.createBox(userDrag, e.getView());

            e.getView().repaint();
            state = TRANSFORM;
            History.addEdit(new CreateBoxedShapeEdit(comp, styledShape, transformBox));
        } else { // selection mode
            comp.onSelection(selection -> addSelectionEdit(comp, selection));
            state = NO_INTERACTION;
        }

        stroke.invalidate();
//        styledShape = null;
    }

    @Override
    public void mouseMoved(MouseEvent e, ImageComponent ic) {
        if (state == TRANSFORM) {
            transformBox.mouseMoved(e);
        }
    }

    /**
     * After this method the shape becomes part of the {@link Drawable}'s
     * pixels (before it was only drawn above it).
     */
    private void finalizeShape(Composition comp) {
        assert transformBox != null;
        assert styledShape != null;

        int thickness = calcThickness();

        ShapeType shapeType = getSelectedType();
        Shape currentShape = shapeType.getShape(userDrag.toImDrag());
        Rectangle shapeBounds = currentShape.getBounds();
        shapeBounds.grow(thickness, thickness);

        Drawable dr = comp.getActiveDrawableOrThrow();
        if (!shapeBounds.isEmpty()) {
            BufferedImage originalImage = dr.getImage();
            History.addToolArea(shapeBounds,
                    originalImage, dr, false, getName());
        }
        // TODO why not simply paint the styledShape
        paintShape(dr, currentShape);

        comp.imageChanged();
        dr.updateIconImage();

        styledShape = null;
        transformBox = null;
    }

    private void addSelectionEdit(Composition comp, Selection selection) {
        selection.clipToCanvasSize(comp); // the selection can be too big
        PixelitorEdit edit;
        if (backupSelectionShape != null) {
            edit = new SelectionChangeEdit("Selection Change",
                    comp, backupSelectionShape);
        } else {
            edit = new NewSelectionEdit(comp, selection.getShape());
        }
        History.addEdit(edit);
    }

    private int calcThickness() {
        ShapesAction action = getSelectedAction();
        int thickness = 0;
        int extraStrokeThickness = 0;
        if (action.hasStrokePaint()) {
            thickness = strokeParam.getStrokeWidth();

            StrokeType strokeType = strokeParam.getStrokeType();
            extraStrokeThickness = strokeType.getExtraThickness(thickness);
            thickness += extraStrokeThickness;
        }

        int effectThickness = effectsParam.getMaxEffectThickness();
        // the extra stroke thickness must be added
        // because the effect can be on the stroke
        effectThickness += extraStrokeThickness;

        if (effectThickness > thickness) {
            thickness = effectThickness;
        }
        return thickness;
    }

    private void regenerateShape(Object source) {
        // TODO do other check like in GradientTool
        if (styledShape != null) {
            if (source == typeModel) {
                styledShape.setType(getSelectedType());
                transformBox.applyTransformation();
            } else if (source == actionModel) {
                ShapesAction newAction = getSelectedAction();
                if (newAction.hasStrokePaint()) {
                    stroke.invalidate();
                }
                styledShape.setAction(newAction);
            } else if (source == fillPaintModel) {
                styledShape.setFillPaint(getSelectedFillPaint());
            } else if (source == strokePaintModel) {
                styledShape.setStrokePaint(getSelectedStrokePaint());
            } else if (source == strokeParam) {
                stroke.invalidate();
                styledShape.setStroke(stroke.get());
            } else if (source == effectsParam) {
                styledShape.setEffects(effectsParam.getEffects());
            }

            ImageComponents.getActiveCompOrNull().imageChanged();
        }
    }

    private void updateEnabledState() {
        ShapesAction action = getSelectedAction();

        enableEffectSettings(action.canHaveEffects());
        enableStrokeSettings(action.hasStrokeSettings());
        fillPaintCombo.setEnabled(action.hasFillPaint());
        strokePaintCombo.setEnabled(action.hasStrokePaint());
    }

    private void initAndShowStrokeSettingsDialog() {
        strokeSettingsDialog = strokeParam.createSettingsDialog();

        GUIUtils.showDialog(strokeSettingsDialog);
    }

    private void closeEffectsDialog() {
        GUIUtils.closeDialog(effectsDialog, true);
    }

    private void closeStrokeDialog() {
        GUIUtils.closeDialog(strokeSettingsDialog, true);
    }

    @Override
    protected void closeToolDialogs() {
        closeStrokeDialog();
        closeEffectsDialog();
    }

    @Override
    public void paintOverLayer(Graphics2D g, Composition comp) {
        if (state == INITIAL_DRAG) {
            // updates the shape continuously while drawing
            Shape currentShape = getSelectedType().getShape(userDrag.toImDrag());
            paintShape(g, currentShape, comp);
        } else if (state == TRANSFORM) {
            assert transformBox != null;
            styledShape.paint(g);
        }
    }

    @Override
    public void paintOverImage(Graphics2D g, Canvas canvas, ImageComponent ic,
                               AffineTransform componentTransform,
                               AffineTransform imageTransform) {
        if (state == INITIAL_DRAG) {
            // paint the drag display for the initial drag
            super.paintOverImage(g, canvas, ic, componentTransform, imageTransform);
        } else if (state == TRANSFORM) {
            assert transformBox != null;
            assert styledShape != null;
            transformBox.paint(g);
        }
    }

    @Override
    public DragDisplayType getDragDisplayType() {
        assert state == INITIAL_DRAG;
        return getSelectedType().getDragDisplayType();
    }

    /**
     * Programmatically draw the current shape type with the given drag
     */
    public void paintDrag(Drawable dr, ImDrag imDrag) {
        Shape shape = getSelectedType().getShape(imDrag);
        paintShape(dr, shape);
    }

    /**
     * Paints a shape on the given Drawable. Can be used programmatically.
     * The start and end point points are given relative to the canvas.
     */
    private void paintShape(Drawable dr, Shape shape) {
        int tx = -dr.getTX();
        int ty = -dr.getTY();

        BufferedImage bi = dr.getImage();
        Graphics2D g2 = bi.createGraphics();
        g2.translate(tx, ty);

        Composition comp = dr.getComp();

        comp.applySelectionClipping(g2);

        paintShape(g2, shape, comp);
        g2.dispose();
    }

    /**
     * Paints the selected shape on the given Graphics2D
     * within the bounds of the current UserDrag
     */
    private void paintShape(Graphics2D g, Shape shape, Composition comp) {
        if (userDrag.isClick()) {
            return;
        }

        ShapesAction action = getSelectedAction();

        if (action.createSelection()) {
            setSelection(shape, comp, action);
        } else {
            styledShape.paint(g);
        }
    }

    private void setSelection(Shape shape, Composition comp, ShapesAction action) {
        ShapeType shapeType = getSelectedType();
        Shape selectionShape = createSelectionShape(shape, action, shapeType);

        Selection selection = comp.getSelection();

        if (selection != null) {
            // this code is called for each drag event:
            // update the selection shape
            selection.setShape(selectionShape);
        } else {
            comp.setSelectionFromShape(selectionShape);
        }
    }

    private Shape createSelectionShape(Shape shape, ShapesAction action, ShapeType shapeType) {
        Shape selectionShape;
        if (action.hasStrokeSettings()) {
            selectionShape = stroke.get().createStrokedShape(shape);
        } else if (!shapeType.isClosed()) {
            selectionShape = STROKE_FOR_OPEN_SHAPES.createStrokedShape(shape);
        } else {
            selectionShape = shape;
        }
        return selectionShape;
    }

    public boolean shouldDrawOverLayer() {
        // TODO if a selection is made, then it could always return false?
        return state == INITIAL_DRAG || state == TRANSFORM;
    }

    private void enableStrokeSettings(boolean b) {
        strokeSettingsButton.setEnabled(b);

        if (!b) {
            closeStrokeDialog();
        }
    }

    private void enableEffectSettings(boolean b) {
        effectsButton.setEnabled(b);

        if (!b) {
            closeEffectsDialog();
        }
    }

    @Override
    public void fgBgColorsChanged() {
        // calling with the action model will make it use the new colors
        regenerateShape(actionModel);
    }

    @Override
    public void coCoordsChanged(ImageComponent ic) {
        if (transformBox != null) {
            transformBox.coCoordsChanged(ic);
        }
    }

    @Override
    public void resetStateToInitial() {
        // true, for example, when the initial box creation is undone
        boolean hadShape = styledShape != null;

        state = NO_INTERACTION;
        transformBox = null;
        styledShape = null;

        Composition comp = ImageComponents.getActiveCompOrNull();
        if (comp != null) { // this gets also called after a "close all"
            if (hadShape) {
                comp.imageChanged();
            } else {
                comp.repaint();
            }
        }
    }

    @Override
    public void compReplaced(Composition oldComp, Composition newComp) {
        resetStateToInitial();
    }

    /**
     * Restores a previously deleted box as part of an undo/redo operation
     */
    public void restoreBox(StyledShape shape, TransformBox box) {
        assert styledShape == null;
        assert transformBox == null;

        styledShape = shape;
        transformBox = box;
        state = TRANSFORM;
        ImageComponents.getActiveCompOrNull().imageChanged();
    }

    @Override
    protected void toolEnded() {
        super.toolEnded();

        // finalize existing box
        if (transformBox != null) {
            finalizeShape(ImageComponents.getActiveCompOrNull());
        }

        resetStateToInitial();
    }

    @Override
    public void activeImageHasChanged(ImageComponent oldIC, ImageComponent newIC) {
        // finalize existing box
        if (transformBox != null) {
            assert styledShape != null;
            finalizeShape(oldIC.getComp());
        } else {
            assert styledShape == null;
        }

        super.activeImageHasChanged(oldIC, newIC);
    }

    private ShapesAction getSelectedAction() {
        return actionModel.getSelectedItem();
    }

    private ShapeType getSelectedType() {
        return typeModel.getSelectedItem();
    }

    public TwoPointBasedPaint getSelectedFillPaint() {
        return fillPaintModel.getSelectedItem();
    }

    public TwoPointBasedPaint getSelectedStrokePaint() {
        return strokePaintModel.getSelectedItem();
    }

    public AreaEffects getEffects() {
        return effectsParam.getEffects();
    }

    public Stroke getStroke() {
        return stroke.get();
    }

    @Override
    public DebugNode getDebugNode() {
        DebugNode node = super.getDebugNode();

        if (transformBox == null) {
            node.addString("transformBox", "null");
        } else {
            node.add(transformBox.getDebugNode());
        }
        if (styledShape == null) {
            node.addString("styledShape", "null");
        } else {
            node.add(styledShape.getDebugNode());
        }

        node.addString("Type", getSelectedType().toString());
        node.addString("Action", getSelectedAction().toString());
        node.addString("Fill", getSelectedFillPaint().toString());
        node.addString("Stroke", getSelectedStrokePaint().toString());
        strokeParam.addDebugNodeInfo(node);

        return node;
    }
}

