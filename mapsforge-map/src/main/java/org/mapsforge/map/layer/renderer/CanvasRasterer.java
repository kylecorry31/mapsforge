/*
 * Copyright 2010, 2011, 2012, 2013 mapsforge.org
 * Copyright 2014-2015 Ludwig M Brinckmann
 * Copyright 2016-2020 devemux86
 * Copyright 2017 usrusr
 * Copyright 2020 Adrian Batzill
 * Copyright 2024-2025 Sublimis
 *
 * This program is free software: you can redistribute it and/or modify it under the
 * terms of the GNU Lesser General Public License as published by the Free Software
 * Foundation, either version 3 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY
 * WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A
 * PARTICULAR PURPOSE. See the GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License along with
 * this program. If not, see <http://www.gnu.org/licenses/>.
 */
package org.mapsforge.map.layer.renderer;

import org.mapsforge.core.graphics.*;
import org.mapsforge.core.mapelements.MapElementContainer;
import org.mapsforge.core.model.Point;
import org.mapsforge.core.model.Rectangle;
import org.mapsforge.core.model.Rotation;
import org.mapsforge.core.model.Tile;
import org.mapsforge.core.util.MercatorProjection;
import org.mapsforge.core.util.Parameters;
import org.mapsforge.map.rendertheme.RenderContext;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

public class CanvasRasterer {
    private static final int DEFAULT_TILE_BOUNDARY_ENLARGEMENT = 20;

    private final RenderContext renderContext;
    private final Canvas canvas;
    private final Path path;
    private final Matrix symbolMatrix;

    /**
     * This will count paths vs. lines usage for performance diagnostics
     */
    private final boolean DEBUG_COUNTS = false;
    private final AtomicInteger linesCount = DEBUG_COUNTS ? new AtomicInteger() : null;
    private final AtomicInteger pathsCount = DEBUG_COUNTS ? new AtomicInteger() : null;


    public CanvasRasterer(RenderContext renderContext, GraphicFactory graphicFactory) {
        this.renderContext = renderContext;
        this.canvas = graphicFactory.createCanvas();
        this.path = graphicFactory.createPath();
        this.symbolMatrix = graphicFactory.createMatrix();
    }

    public void destroy() {
        this.canvas.destroy();

        if (DEBUG_COUNTS) {
            System.out.println("LINES: " + linesCount.get() + "  PATHS: " + pathsCount.get());
        }
    }

    /**
     * Input is assumed to already be sorted by drawing priority.
     */
    void drawMapElements(List<MapElementContainer> elements, Tile tile) {
        for (MapElementContainer element : elements) {
            element.draw(canvas, tile.getOrigin(), this.symbolMatrix, Rotation.NULL_ROTATION);
        }
    }

    void fill(int color) {
        if (GraphicUtils.getAlpha(color) > 0) {
            this.canvas.fillColor(color);
        }
    }

    /**
     * Fills the area outside the specified rectangle with color. Use this method when
     * overpainting with a transparent color as it sets the PorterDuff mode.
     * This method is used to blank out areas that fall outside the map area.
     *
     * @param color      the fill color for the outside area
     * @param insideArea the inside area on which not to draw
     */
    void fillOutsideAreas(Color color, Rectangle insideArea) {

        // Clamp input to prevent overwhelming the canvas with extreme coordinate values
        insideArea = insideArea.clampClipCoordinates(this.canvas.getWidth(), this.canvas.getHeight());

        this.canvas.setClipDifference((float) insideArea.left, (float) insideArea.top, (float) insideArea.getWidth(), (float) insideArea.getHeight());
        this.canvas.fillColor(color);
        this.canvas.resetClip();
    }

    /**
     * Fills the area outside the specified rectangle with color.
     * This method is used to blank out areas that fall outside the map area.
     *
     * @param color      the fill color for the outside area
     * @param insideArea the inside area on which not to draw
     */
    void fillOutsideAreas(int color, Rectangle insideArea) {

        // Clamp input to prevent overwhelming the canvas with extreme coordinate values
        insideArea = insideArea.clampClipCoordinates(this.canvas.getWidth(), this.canvas.getHeight());

        this.canvas.setClipDifference((float) insideArea.left, (float) insideArea.top, (float) insideArea.getWidth(), (float) insideArea.getHeight());
        this.canvas.fillColor(color);
        this.canvas.resetClip();
    }

    void setCanvasBitmap(Bitmap bitmap) {
        this.canvas.setBitmap(bitmap);
    }

    private void drawCircleContainer(ShapePaintContainer shapePaintContainer) {
        CircleContainer circleContainer = (CircleContainer) shapePaintContainer.shapeContainer;
        Point point = circleContainer.point;
        this.canvas.drawCircle((int) point.x, (int) point.y, (int) circleContainer.radius, shapePaintContainer.paint);
    }

    private void drawHillshading(HillshadingContainer container) {
        final Bitmap bitmap = container.bitmap;

        // Synchronized to prevent concurrent modification by other threads e.g. while merging neighbors
        synchronized (bitmap.getMutex()) {
            canvas.shadeBitmap(bitmap, container.hillsRect, container.tileRect, container.magnitude, container.color, container.external);
        }
    }

    private void drawPath(ShapePaintContainer shapePaintContainer, Point[][] coordinates, float dy) {
        if (shapePaintContainer.omitTileBoundarySegments && shapePaintContainer.shapeContainer instanceof PolylineContainer) {
            PolylineContainer polylineContainer = (PolylineContainer) shapePaintContainer.shapeContainer;
            Rectangle sourceTileBoundary = getSourceTileBoundary(polylineContainer);
            if (sourceTileBoundary != null) {
                double enlargement = getSourceTileBoundaryEnlargement(polylineContainer);
                coordinates = removeTileBoundarySegments(coordinates, sourceTileBoundary, enlargement);
            }
        }

        if (shapePaintContainer.curveStyle == Curve.CUBIC) {
            // When cubic, paths must be used.
            makeCubicPath(coordinates, dy, this.path);
            drawPath(shapePaintContainer);
        } else if (shapePaintContainer.omitTileBoundarySegments) {
            makeLinesPath(coordinates, dy, this.path);
            drawPath(shapePaintContainer);
        } else if (shapePaintContainer.paint.isComplexStyle()) {
            // When complex (e.g. filled) style, paths must be used.
            makeLinesPath(coordinates, dy, this.path);
            drawPath(shapePaintContainer);
        } else {
            // When neither cubic nor complex style, use lines (esp. on Android):
            //   * To prevent libhwui.so "null pointer dereference" SIGSEGV crashes.
            //   * For performance.
            drawLines(shapePaintContainer, coordinates, dy);
        }
    }

    private void drawPath(ShapePaintContainer shapePaintContainer) {
        if (!this.path.isEmpty()) {
            if (Parameters.NUMBER_OF_THREADS > 1) {
                // Make sure setting the shader shift and actual drawing is synchronized,
                // since the paint object is shared between multiple threads.
                synchronized (shapePaintContainer.paint) {
                    final RenderContext renderContext = this.renderContext;
                    shapePaintContainer.paint.setBitmapShaderShift(renderContext.rendererJob.tile.getOrigin());
                    this.canvas.drawPath(this.path, shapePaintContainer.paint);
                }
            } else {
                this.canvas.drawPath(this.path, shapePaintContainer.paint);
            }

            if (DEBUG_COUNTS) {
                pathsCount.incrementAndGet();
            }
        }
    }

    private void drawLines(ShapePaintContainer shapePaintContainer, Point[][] coordinates, float dy) {
        if (Parameters.NUMBER_OF_THREADS > 1) {
            // Make sure setting the shader shift and actual drawing is synchronized,
            // since the paint object is shared between multiple threads.
            synchronized (shapePaintContainer.paint) {
                final RenderContext renderContext = this.renderContext;
                shapePaintContainer.paint.setBitmapShaderShift(renderContext.rendererJob.tile.getOrigin());
                this.canvas.drawLines(coordinates, dy, shapePaintContainer.paint);
            }
        } else {
            this.canvas.drawLines(coordinates, dy, shapePaintContainer.paint);
        }

        if (DEBUG_COUNTS) {
            linesCount.incrementAndGet();
        }
    }

    public void drawShapePaintContainer(ShapePaintContainer shapePaintContainer) {
        ShapeContainer shapeContainer = shapePaintContainer.shapeContainer;
        ShapeType shapeType = shapeContainer.getShapeType();
        switch (shapeType) {
            case CIRCLE:
                drawCircleContainer(shapePaintContainer);
                break;
            case HILLSHADING:
                HillshadingContainer hillshadingContainer = (HillshadingContainer) shapeContainer;
                drawHillshading(hillshadingContainer);
                break;
            case POLYLINE:
                PolylineContainer polylineContainer = (PolylineContainer) shapeContainer;
                drawPath(shapePaintContainer, polylineContainer.getCoordinatesRelativeToOrigin(), shapePaintContainer.dy);
                break;
        }
    }

    private static void makeCubicPath(Point[][] coordinates, float dy, Path path) {
        path.clear();

        for (Point[] innerList : coordinates) {
            final Point[] points = dy == 0f ? innerList : RendererUtils.parallelPath(innerList, dy);
            if (points.length >= 2) {
                float[] p1 = new float[]{(float) points[0].x, (float) points[0].y};
                float[] p2 = new float[]{0.0f, 0.0f};
                float[] p3 = new float[]{0.0f, 0.0f};

                // add first point
                path.moveTo(p1[0], p1[1]);

                for (int i = 1; i < points.length; ++i) {
                    // get ending coordinates
                    p3[0] = (float) points[i].x;
                    p3[1] = (float) points[i].y;
                    p2[0] = 0.5f * (p1[0] + p3[0]);
                    p2[1] = 0.5f * (p1[1] + p3[1]);

                    // add spline over middle point and end on 'end' point
                    path.quadTo(p1[0], p1[1], p2[0], p2[1]);

                    // store end point as start point for next section
                    p1[0] = p3[0];
                    p1[1] = p3[1];
                }

                // add last segment
                path.quadTo(p2[0], p2[1], p3[0], p3[1]);
            }
        }
    }

    private static void makeLinesPath(Point[][] coordinates, float dy, Path path) {
        path.clear();

        for (Point[] innerList : coordinates) {
            final Point[] points = dy == 0f ? innerList : RendererUtils.parallelPath(innerList, dy);
            if (points.length >= 2) {
                path.moveTo((float) points[0].x, (float) points[0].y);

                for (int i = 1; i < points.length; ++i) {
                    path.lineTo((float) points[i].x, (float) points[i].y);
                }
            }
        }
    }

    private static Point[][] removeTileBoundarySegments(Point[][] coordinates, Rectangle tileBoundary, double enlargement) {
        double tolerance = 1d;
        List<Point[]> result = new ArrayList<>();

        for (Point[] points : coordinates) {
            List<Point> current = new ArrayList<>();
            for (int i = 1; i < points.length; ++i) {
                Point previous = points[i - 1];
                Point next = points[i];
                if (isTileBoundarySegment(previous, next, tileBoundary, enlargement, tolerance)) {
                    addSegment(result, current);
                    current.clear();
                } else {
                    if (current.isEmpty()) {
                        current.add(previous);
                    }
                    current.add(next);
                }
            }
            addSegment(result, current);
        }

        return result.toArray(new Point[result.size()][]);
    }

    private Rectangle getSourceTileBoundary(PolylineContainer polylineContainer) {
        Tile sourceTile = polylineContainer.getSourceTile();
        if (sourceTile == null) {
            return null;
        }

        Tile renderTile = this.renderContext.rendererJob.tile;
        double zoomScale = Math.pow(2, sourceTile.zoomLevel - renderTile.zoomLevel);
        double sourceTileSize = renderTile.tileSize / zoomScale;
        Point renderTileOrigin = renderTile.getOrigin();
        double left = sourceTile.tileX * sourceTileSize - renderTileOrigin.x;
        double top = sourceTile.tileY * sourceTileSize - renderTileOrigin.y;
        return new Rectangle(left, top, left + sourceTileSize, top + sourceTileSize);
    }

    private double getSourceTileBoundaryEnlargement(PolylineContainer polylineContainer) {
        Tile sourceTile = polylineContainer.getSourceTile();
        double latitude = MercatorProjection.tileYToLatitude(sourceTile.tileY, sourceTile.zoomLevel);
        return MercatorProjection.metersToPixels(DEFAULT_TILE_BOUNDARY_ENLARGEMENT, latitude,
                this.renderContext.rendererJob.tile.mapSize);
    }

    private static void addSegment(List<Point[]> result, List<Point> points) {
        if (points.size() >= 2) {
            result.add(points.toArray(new Point[points.size()]));
        }
    }

    private static boolean isTileBoundarySegment(Point from, Point to, Rectangle tileBoundary,
                                                 double enlargement, double tolerance) {
        double left = tileBoundary.left - enlargement;
        double right = tileBoundary.right + enlargement;
        double top = tileBoundary.top - enlargement;
        double bottom = tileBoundary.bottom + enlargement;

        return isVerticalBoundarySegment(from, to, left, tileBoundary.left, top, bottom, tolerance)
                || isVerticalBoundarySegment(from, to, tileBoundary.right, right, top, bottom, tolerance)
                || isHorizontalBoundarySegment(from, to, top, tileBoundary.top, left, right, tolerance)
                || isHorizontalBoundarySegment(from, to, tileBoundary.bottom, bottom, left, right, tolerance);
    }

    private static boolean isVerticalBoundarySegment(Point from, Point to, double minX, double maxX,
                                                     double minY, double maxY, double tolerance) {
        return isSameCoordinate(from.x, to.x, tolerance)
                && isBetween(from.x, minX, maxX, tolerance)
                && isBetween(to.x, minX, maxX, tolerance)
                && isBetween(from.y, to.y, minY, maxY, tolerance);
    }

    private static boolean isHorizontalBoundarySegment(Point from, Point to, double minY, double maxY,
                                                       double minX, double maxX, double tolerance) {
        return isSameCoordinate(from.y, to.y, tolerance)
                && isBetween(from.y, minY, maxY, tolerance)
                && isBetween(to.y, minY, maxY, tolerance)
                && isBetween(from.x, to.x, minX, maxX, tolerance);
    }

    private static boolean isSameCoordinate(double first, double second, double tolerance) {
        return Math.abs(first - second) <= tolerance;
    }

    private static boolean isBetween(double from, double to, double start, double end, double tolerance) {
        return isBetween(to, start, end, tolerance) && isBetween(from, start, end, tolerance);
    }

    private static boolean isBetween(double value, double start, double end, double tolerance) {
        return value >= start - tolerance && value <= end + tolerance;
    }
}
