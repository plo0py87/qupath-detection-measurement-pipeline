import qupath.lib.gui.viewer.overlays.BufferedImageOverlay
import java.awt.image.BufferedImage
import java.awt.Graphics2D
import java.awt.BasicStroke
import java.awt.RenderingHints
import java.awt.Color
import java.awt.geom.AffineTransform
import qupath.lib.objects.PathAnnotationObject
import qupath.lib.color.ColorMaps
import javafx.application.Platform

// ==================================================
// ================= USER PARAMETERS =================
// ==================================================

// ---------- Viewer ----------
final double TARGET_DOWNSAMPLE = 5.0
// ---------- Measurement (non-exclusive name) ----------
final String MEAS_NAME = "Cell density (cells/mm^2)"

// ---------- Overlay resolution (IMPORTANT: >= 1.0) ----------
final double MASK_DOWNSAMPLE = 1.0   // < 1 會導致 OOM

// ---------- Color scale ----------
final String SCALE_MODE = "percentile"   // "data" | "percentile" | "manual"
final double P_LOW = 2
final double P_HIGH = 98
final double V_MIN = 0
final double V_MAX = 1

final String COLORMAP_NAME = "Verdis" // Available colormaps: "Blue", "Cyan", "Gray", "Green", "Inferno", "Jet", "Magenta", "Magma", "Plasma", "Red", "Svidro2", "Viridis", "Yellow"

// ---------- Annotation drawing ----------
final boolean DRAW_OUTLINE = true
final float OUTLINE_PX = 2f

// ---------- Background ----------
final boolean SIMULATE_HIDE_IMAGE = true
final Color BACKGROUND_COLOR = new Color(255, 255, 255, 255)

// ---------- Viewer annotation visibility ----------
final boolean HIDE_QP_ANNOS = false

// ---------- Scale bar ----------
final double SCALEBAR_UM = 1000          // >=1000 → mm
final int SCALEBAR_BAR_HEIGHT_PX = 50
final int SCALEBAR_MARGIN_PX = 90

final Color SCALEBAR_COLOR = Color.BLACK
final Color SCALEBAR_TEXT_COLOR = Color.BLACK
final String SCALEBAR_FONT_NAME = "Arial"
final int SCALEBAR_FONT_SIZE = 200
final boolean DRAW_SCALEBAR_TEXT = true

// ==================================================
// ================= VIEWER & DATA ===================
// ==================================================

def viewer = getCurrentViewer()
if (viewer == null) {
    println "No active viewer"
    return
}

def imageData = viewer.getImageData()
def hierarchy = imageData.getHierarchy()
def server = imageData.getServer()

def allAnns = hierarchy.getAnnotationObjects()
        .findAll { it instanceof PathAnnotationObject && it.getROI() != null }

if (allAnns.isEmpty()) {
    println "No annotations found"
    return
}

// ==================================================
// ================= VIEW CENTER =====================
// ==================================================

double minX = Double.POSITIVE_INFINITY
double minY = Double.POSITIVE_INFINITY
double maxX = Double.NEGATIVE_INFINITY
double maxY = Double.NEGATIVE_INFINITY

allAnns.each { a ->
    def r = a.getROI()
    minX = Math.min(minX, r.getBoundsX())
    minY = Math.min(minY, r.getBoundsY())
    maxX = Math.max(maxX, r.getBoundsX() + r.getBoundsWidth())
    maxY = Math.max(maxY, r.getBoundsY() + r.getBoundsHeight())
}

double centerX = (minX + maxX) / 2.0
double centerY = (minY + maxY) / 2.0

Platform.runLater {
    viewer.setDownsampleFactor(TARGET_DOWNSAMPLE)
    viewer.setCenterPixelLocation(centerX, centerY)
    viewer.repaint()
}

println "Viewer centered at (${(int)centerX}, ${(int)centerY}), downsample=${TARGET_DOWNSAMPLE}"

// ==================================================
// ================= COLORMAP ========================
// ==================================================

def cmap = ColorMaps.getColorMaps().getOrDefault(
        COLORMAP_NAME,
        ColorMaps.getDefaultColorMap()
)

// ==================================================
// ============ MEASUREMENT (Exclusive-first) ========
// ==================================================

List<String> candidateKeys = []
String base = MEAS_NAME.trim()

candidateKeys << "Exclusive " + base
candidateKeys << base

def measOf = { PathAnnotationObject a ->
    def ml = a.getMeasurementList()
    for (k in candidateKeys) {
        double v = ml.getOrDefault(k, Double.NaN) as double
        if (Double.isFinite(v)) return v
    }
    return Double.NaN
}

def values = allAnns.collect { measOf(it) }
        .findAll { Double.isFinite(it) }

if (values.isEmpty()) {
    println "No valid measurement values"
    return
}

values.sort()

double vmin, vmax
if (SCALE_MODE.equalsIgnoreCase("data")) {
    vmin = values.first()
    vmax = values.last()
} else if (SCALE_MODE.equalsIgnoreCase("percentile")) {
    vmin = values[(int)(P_LOW / 100.0 * (values.size() - 1))]
    vmax = values[(int)(P_HIGH / 100.0 * (values.size() - 1))]
} else {
    vmin = V_MIN
    vmax = V_MAX
}

if (vmax <= vmin) vmax = vmin + 1e-9

println "Color scale: [${vmin}, ${vmax}]"

// ==================================================
// ================= BUILD OVERLAY ===================
// ==================================================

int W = Math.ceil(server.getWidth() / MASK_DOWNSAMPLE) as int
int H = Math.ceil(server.getHeight() / MASK_DOWNSAMPLE) as int

def img = new BufferedImage(W, H, BufferedImage.TYPE_INT_ARGB)
Graphics2D g = img.createGraphics()
g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)

def scale = AffineTransform.getScaleInstance(
        1.0 / MASK_DOWNSAMPLE,
        1.0 / MASK_DOWNSAMPLE
)

if (SIMULATE_HIDE_IMAGE) {
    g.setColor(BACKGROUND_COLOR)
    g.fillRect(0, 0, W, H)
}

// Draw annotations (parent first)
def depthOf = { a ->
    int d = 0
    def p = a.getParent()
    while (p instanceof PathAnnotationObject) {
        d++
        p = p.getParent()
    }
    d
}

allAnns.sort { a, b -> depthOf(a) <=> depthOf(b) }

allAnns.each { a ->
    double v = measOf(a)
    if (!Double.isFinite(v)) return

    g.setColor(new Color(cmap.getColor(v, vmin, vmax), true))
    def shape = scale.createTransformedShape(a.getROI().getShape())
    g.fill(shape)

    if (DRAW_OUTLINE) {
        g.setColor(Color.BLACK)
        g.setStroke(new BasicStroke(OUTLINE_PX))
        g.draw(shape)
    }
}

// ==================================================
// ================= DRAW SCALE BAR ==================
// ==================================================

def cal = server.getPixelCalibration()
double umPerPx = cal.hasPixelSizeMicrons()
        ? cal.getAveragedPixelSizeMicrons()
        : 1.0

int barLenPx = Math.round(SCALEBAR_UM / umPerPx / MASK_DOWNSAMPLE) as int
barLenPx = Math.min(barLenPx, (int)(W * 0.4))

int x0 = SCALEBAR_MARGIN_PX
int y0 = H - SCALEBAR_MARGIN_PX - SCALEBAR_BAR_HEIGHT_PX

// background plate
g.setColor(new Color(255, 255, 255, 220))
g.fillRect(x0 - 20, y0 - 120, barLenPx + 40, 140)

// bar
g.setColor(SCALEBAR_COLOR)
g.fillRect(x0, y0, barLenPx, SCALEBAR_BAR_HEIGHT_PX)

// label
if (DRAW_SCALEBAR_TEXT) {
    g.setFont(new java.awt.Font(
            SCALEBAR_FONT_NAME,
            java.awt.Font.PLAIN,
            SCALEBAR_FONT_SIZE
    ))
    g.setColor(SCALEBAR_TEXT_COLOR)

    String label = (SCALEBAR_UM >= 1000)
            ? "${(int)(SCALEBAR_UM / 1000)} mm"
            : "${(int)SCALEBAR_UM} µm"

    g.drawString(label, x0, y0 - 20)
}

g.dispose()

// ==================================================
// ================= ADD OVERLAY =====================
// ==================================================

def overlay = new BufferedImageOverlay(viewer, img)
viewer.getCustomOverlayLayers().removeIf { it instanceof BufferedImageOverlay }
viewer.getCustomOverlayLayers().add(overlay)

println "Overlay rendered successfully"
