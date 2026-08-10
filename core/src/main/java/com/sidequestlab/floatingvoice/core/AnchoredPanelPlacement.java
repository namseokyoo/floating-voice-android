package com.sidequestlab.floatingvoice.core;

public final class AnchoredPanelPlacement {
    public enum Side {
        LEFT,
        RIGHT
    }

    public record Placement(int x, int y, Side side) {
    }

    private AnchoredPanelPlacement() {
    }

    public static Placement place(
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
        validateArguments(
                displayWidth, displayHeight,
                bubbleLeft, bubbleTop, bubbleRight, bubbleBottom,
                panelWidth, panelHeight,
                margin, gap);

        int minimumX = margin;
        int maximumX = displayWidth - margin - panelWidth;
        long rightX = (long) bubbleRight + gap;
        long leftX = (long) bubbleLeft - gap - panelWidth;

        Side side;
        if (rightX <= maximumX) {
            side = Side.RIGHT;
        } else if (leftX >= minimumX) {
            side = Side.LEFT;
        } else {
            long rightSpace = (long) displayWidth - margin - bubbleRight - gap;
            long leftSpace = (long) bubbleLeft - gap - margin;
            side = rightSpace >= leftSpace ? Side.RIGHT : Side.LEFT;
        }

        long requestedX = side == Side.RIGHT ? rightX : leftX;
        int x = clamp(requestedX, minimumX, maximumX);
        int y = clamp(bubbleTop, margin, displayHeight - margin - panelHeight);
        return new Placement(x, y, side);
    }

    private static void validateArguments(
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
        if (displayWidth <= 0 || displayHeight <= 0) {
            throw new IllegalArgumentException("display dimensions must be positive");
        }
        if ((long) bubbleRight - bubbleLeft <= 0 || (long) bubbleBottom - bubbleTop <= 0) {
            throw new IllegalArgumentException("bubble dimensions must be positive");
        }
        if (panelWidth <= 0 || panelHeight <= 0) {
            throw new IllegalArgumentException("panel dimensions must be positive");
        }
        if (margin < 0) {
            throw new IllegalArgumentException("margin must be nonnegative");
        }
        if (gap < 0) {
            throw new IllegalArgumentException("gap must be nonnegative");
        }
        if ((long) margin + panelWidth + margin > displayWidth
                || (long) margin + panelHeight + margin > displayHeight) {
            throw new IllegalArgumentException("panel must fit inside the safe display bounds");
        }
    }

    private static int clamp(long value, int minimum, int maximum) {
        return (int) Math.max(minimum, Math.min(value, maximum));
    }
}
