package com.sidequestlab.floatingvoice.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AnchoredPanelPlacementTest {
    @Test
    void bubbleOnLeftPrefersPanelOnRight() {
        AnchoredPanelPlacement.Placement placement = AnchoredPanelPlacement.place(
                400, 800,
                16, 200, 80, 264,
                120, 160,
                16, 8);

        assertEquals(new AnchoredPanelPlacement.Placement(
                88, 200, AnchoredPanelPlacement.Side.RIGHT), placement);
        assertInsideSafeBounds(placement, 120, 160, 400, 800, 16);
    }

    @Test
    void preferredRightSideWinsWhenPanelExactlyFitsSafeBoundary() {
        AnchoredPanelPlacement.Placement placement = AnchoredPanelPlacement.place(
                400, 800,
                256, 200, 320, 264,
                56, 160,
                16, 8);

        assertEquals(new AnchoredPanelPlacement.Placement(
                328, 200, AnchoredPanelPlacement.Side.RIGHT), placement);
        assertInsideSafeBounds(placement, 56, 160, 400, 800, 16);
    }

    @Test
    void fallsBackToLeftWhenPreferredRightSideDoesNotFit() {
        AnchoredPanelPlacement.Placement placement = AnchoredPanelPlacement.place(
                400, 800,
                320, 200, 384, 264,
                120, 160,
                16, 8);

        assertEquals(new AnchoredPanelPlacement.Placement(
                192, 200, AnchoredPanelPlacement.Side.LEFT), placement);
        assertInsideSafeBounds(placement, 120, 160, 400, 800, 16);
    }

    @Test
    void upperAndLowerEdgesClampPanelVertically() {
        AnchoredPanelPlacement.Placement upper = AnchoredPanelPlacement.place(
                400, 800,
                16, 0, 80, 64,
                120, 160,
                16, 8);
        AnchoredPanelPlacement.Placement lower = AnchoredPanelPlacement.place(
                400, 800,
                16, 736, 80, 800,
                120, 160,
                16, 8);
        AnchoredPanelPlacement.Placement exactBottom = AnchoredPanelPlacement.place(
                400, 800,
                16, 624, 80, 688,
                120, 160,
                16, 8);

        assertAll(
                () -> assertEquals(16, upper.y()),
                () -> assertEquals(624, lower.y()),
                () -> assertEquals(624, exactBottom.y()));
        assertInsideSafeBounds(upper, 120, 160, 400, 800, 16);
        assertInsideSafeBounds(lower, 120, 160, 400, 800, 16);
        assertInsideSafeBounds(exactBottom, 120, 160, 400, 800, 16);
    }

    @Test
    void panelWiderThanEitherFreeSideUsesLargerSideAndStaysSafe() {
        AnchoredPanelPlacement.Placement placement = AnchoredPanelPlacement.place(
                400, 800,
                240, 200, 300, 264,
                240, 160,
                16, 8);

        assertEquals(new AnchoredPanelPlacement.Placement(
                16, 200, AnchoredPanelPlacement.Side.LEFT), placement);
        assertInsideSafeBounds(placement, 240, 160, 400, 800, 16);
    }

    @Test
    void dimensionsMustBePositiveAndMarginAndGapMustBeNonnegative() {
        assertAll(
                () -> assertThrows(IllegalArgumentException.class,
                        () -> placeWith(0, 800, 16, 200, 80, 264, 120, 160, 16, 8)),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> placeWith(400, -1, 16, 200, 80, 264, 120, 160, 16, 8)),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> placeWith(400, 800, 16, 200, 16, 264, 120, 160, 16, 8)),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> placeWith(400, 800, 16, 200, 80, 200, 120, 160, 16, 8)),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> placeWith(400, 800, 16, 200, 80, 264, 0, 160, 16, 8)),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> placeWith(400, 800, 16, 200, 80, 264, 120, -1, 16, 8)),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> placeWith(400, 800, 16, 200, 80, 264, 120, 160, -1, 8)),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> placeWith(400, 800, 16, 200, 80, 264, 120, 160, 16, -1)));
    }

    @Test
    void panelMustFitInsideSafeDisplayBounds() {
        assertAll(
                () -> assertThrows(IllegalArgumentException.class,
                        () -> placeWith(400, 800, 16, 200, 80, 264, 369, 160, 16, 8)),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> placeWith(400, 800, 16, 200, 80, 264, 120, 769, 16, 8)));
    }

    private static AnchoredPanelPlacement.Placement placeWith(
            int displayWidth,
            int displayHeight,
            int bubbleLeft,
            int bubbleTop,
            int bubbleRight,
            int bubbleBottom,
            int panelWidth,
            int panelHeight,
            int margin,
            int gap) {
        return AnchoredPanelPlacement.place(
                displayWidth, displayHeight,
                bubbleLeft, bubbleTop, bubbleRight, bubbleBottom,
                panelWidth, panelHeight,
                margin, gap);
    }

    private static void assertInsideSafeBounds(
            AnchoredPanelPlacement.Placement placement,
            int panelWidth,
            int panelHeight,
            int displayWidth,
            int displayHeight,
            int margin) {
        assertAll(
                () -> assertTrue(placement.x() >= margin, "left edge outside safe bounds"),
                () -> assertTrue(placement.y() >= margin, "top edge outside safe bounds"),
                () -> assertTrue(placement.x() + panelWidth <= displayWidth - margin,
                        "right edge outside safe bounds"),
                () -> assertTrue(placement.y() + panelHeight <= displayHeight - margin,
                        "bottom edge outside safe bounds"));
    }
}
