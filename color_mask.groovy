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

// ================= USER PARAMETERS =================

// Viewer 縮放倍率
final double TARGET_DOWNSAMPLE = 5.0

// Non-exclusive measurement 欄位（不須填exclusive）
final String MEAS_NAME = "Cell density (cells/mm^2)"

// Overlay 解析度（image / DOWNSAMPLE）
final double MASK_DOWNSAMPLE = 1.0

// 色階設定
final String SCALE_MODE = "percentile"   // "data" | "percentile" | "manual"
final double P_LOW = 2, P_HIGH = 98
final double V_MIN = 0, V_MAX = 1

// 色圖
final String COLORMAP_NAME = "Cyan"

// 畫外框
final boolean DRAW_OUTLINE = false
final float OUTLINE_PX = 2f

// 白底遮原圖
final boolean SIMULATE_HIDE_IMAGE = true
final Color BACKGROUND_COLOR = new Color(255, 255, 255, 255)

// 是否隱藏 QuPath annotation
final boolean HIDE_QP_ANNOS = false

// ================= VIEWER & DATA =================

def viewer = getCurrentViewer()
if (viewer == null) {
    println "No active viewer"
    return
}

def imageData = viewer.getImageData()
def hierarchy = imageData.getHierarchy()

def allAnns = hierarchy.getAnnotationObjects()
        .findAll { it instanceof PathAnnotationObject && it.getROI() != null }

if (allAnns.isEmpty()) {
    println "No annotations found"
    return
}

// ================= COMPUTE GLOBAL CENTER =================

double minX = Double.POSITIVE_INFINITY
double minY = Double.POSITIVE_INFINITY
double maxX = Double.NEGATIVE_INFINITY
double maxY = Double.NEGATIVE_INFINITY

allAnns.each { ann ->
    def r = ann.getROI()
    minX = Math.min(minX, r.getBoundsX())
    minY = Math.min(minY, r.getBoundsY())
    maxX = Math.max(maxX, r.getBoundsX() + r.getBoundsWidth())
    maxY = Math.max(maxY, r.getBoundsY() + r.getBoundsHeight())
}

double centerX = (minX + maxX) / 2.0
double centerY = (minY + maxY) / 2.0

// ================= APPLY VIEW (FX THREAD SAFE) =================

Platform.runLater {
    viewer.setDownsampleFactor(TARGET_DOWNSAMPLE)
    viewer.setCenterPixelLocation(centerX, centerY)
    viewer.repaint()
}

println "Viewer centered at (${(int)centerX}, ${(int)centerY}), downsample=${TARGET_DOWNSAMPLE}"

// ================= SEE COLOR MAPS =================

def cmapMap = ColorMaps.getColorMaps()
def cmap = cmapMap.get(COLORMAP_NAME)
if (cmap == null) {
    println "Colormap '${COLORMAP_NAME}' not found, using default"
    cmap = ColorMaps.getDefaultColorMap()
}

// ================= MEASUREMENT (Exclusive-first) =================

// Build candidate keys in priority order
List<String> candidateKeys = []
String base = MEAS_NAME?.trim()

if (base == null || base.isEmpty()) {
    println "MEAS_NAME is empty"
    return
}

// If user already provided an Exclusive key, just use it (and also allow fallback to non-exclusive by stripping)
if (base.toLowerCase().startsWith("exclusive ")) {
    candidateKeys.add(base)
    candidateKeys.add(base.substring("exclusive ".length()).trim())
} else {
    // Preferred exclusive naming from your measurement script
    candidateKeys.add("Exclusive " + base)
    // Fallback to non-exclusive
    candidateKeys.add(base)
}

// Extra conservative fallbacks for common patterns (only if user asked region mean-like keys)
if (base.toLowerCase().startsWith("region mean")) {
    // e.g. "Region mean (Channel 1)" -> "Exclusive region mean (Channel 1)"
    candidateKeys.add(0, "Exclusive " + base)
}
if (base.toLowerCase().startsWith("[") || base.toLowerCase().contains("]")) {
    // e.g. "[Whole] inside mean ..." -> "Exclusive [Whole] inside mean ..."
    candidateKeys.add(0, "Exclusive " + base)
}

// Measurement getter: try keys in order, pick first finite value
def measOf = { PathAnnotationObject a ->
    def ml = a.getMeasurementList()
    for (String k : candidateKeys) {
        if (k == null) continue
        double v = ml.getOrDefault(k, Double.NaN) as double
        if (Double.isFinite(v))
            return v
    }
    return Double.NaN
}

// Also allow us to debug which key was used (optional)
def keyUsedOf = { PathAnnotationObject a ->
    def ml = a.getMeasurementList()
    for (String k : candidateKeys) {
        if (k == null) continue
        double v = ml.getOrDefault(k, Double.NaN) as double
        if (Double.isFinite(v))
            return k
    }
    return null
}

// Collect values
def vals = allAnns.collect { measOf(it) }
        .findAll { Double.isFinite(it) }

if (vals.isEmpty()) {
    println "No valid measurement values for keys: " + candidateKeys
    return
}

// Quick sanity: show a few examples of which key is used
int shown = 0
for (a in allAnns) {
    def k = keyUsedOf(a)
    if (k != null) {
        println "Example key used: '${k}'"
        break
    }
}

// ================= MEASUREMENT RANGE =================

vals.sort()
double vmin, vmax

if (SCALE_MODE.equalsIgnoreCase("data")) {
    vmin = vals.first()
    vmax = vals.last()
} else if (SCALE_MODE.equalsIgnoreCase("percentile")) {
    int iL = Math.round((P_LOW / 100.0) * (vals.size() - 1))
    int iH = Math.round((P_HIGH / 100.0) * (vals.size() - 1))
    vmin = vals[iL]
    vmax = vals[iH]
} else {
    vmin = V_MIN
    vmax = V_MAX
}

if (vmax <= vmin)
    vmax = vmin + 1e-9

println "Color scale: [${vmin}, ${vmax}] using keys priority: " + candidateKeys

// ================= BUILD OVERLAY =================

def server = imageData.getServer()
int W = Math.ceil(server.getWidth() / MASK_DOWNSAMPLE) as int
int H = Math.ceil(server.getHeight() / MASK_DOWNSAMPLE) as int

def img = new BufferedImage(W, H, BufferedImage.TYPE_INT_ARGB)
Graphics2D g = img.createGraphics()
g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)

def scale = AffineTransform.getScaleInstance(
        1.0 / MASK_DOWNSAMPLE,
        1.0 / MASK_DOWNSAMPLE
)

// 白底
if (SIMULATE_HIDE_IMAGE) {
    g.setColor(BACKGROUND_COLOR)
    g.fillRect(0, 0, W, H)
}

// 依 hierarchy 深度排序（父層先畫）
def depthOf = { PathAnnotationObject a ->
    int d = 0
    def p = a.getParent()
    while (p instanceof PathAnnotationObject) {
        d++
        p = p.getParent()
    }
    return d
}

allAnns.sort { a, b -> depthOf(a) <=> depthOf(b) }

// 畫 mask
allAnns.each { a ->
    double v = measOf(a)
    if (!Double.isFinite(v)) return

    int argb = cmap.getColor(v, vmin, vmax)
    g.setColor(new Color(argb, true))

    def shape = scale.createTransformedShape(a.getROI().getShape())
    g.fill(shape)

    if (DRAW_OUTLINE) {
        g.setColor(Color.BLACK)
        g.setStroke(new BasicStroke(OUTLINE_PX))
        g.draw(shape)
    }
}
// ================= DRAW SCALE BAR (FORCED & SAFE) =================

// ---- 使用者設定 ----
double SCALEBAR_UM = 1000
int BAR_HEIGHT_PX = 50
int MARGIN_PX = 90

Color BAR_COLOR = Color.BLACK
Color TEXT_COLOR = Color.BLACK
String FONT_NAME = "Arial"
int FONT_SIZE = 200
boolean DRAW_SCALEBAR_TEXT = true

// ---- pixel calibration ----
def cal = imageData.getServer().getPixelCalibration()
double umPerPx = cal.hasPixelSizeMicrons()
        ? cal.getAveragedPixelSizeMicrons()
        : 1.0

// ---- 計算 bar 長度（overlay 座標）----
double barLenPxD = SCALEBAR_UM / umPerPx / MASK_DOWNSAMPLE
int barLenPx = (int)Math.round(barLenPxD)

// ---- 防呆：bar 太長就縮到畫面 40% ----
int maxBar = (int)(W * 0.4)
if (barLenPx > maxBar) {
    barLenPx = maxBar
}

// ---- 座標防呆：確保在畫布內 ----
int x0 = MARGIN_PX
int y0 = H - MARGIN_PX - BAR_HEIGHT_PX

if (x0 < MARGIN_PX) x0 = MARGIN_PX
if (y0 < MARGIN_PX) y0 = H - MARGIN_PX - BAR_HEIGHT_PX

// ---- DEBUG（一定要留，確認有進來）----
println "[ScaleBar] W=${W}, H=${H}, barLenPx=${barLenPx}, x0=${x0}, y0=${y0}"

// ---- 畫一個半透明底，避免被白底吃掉（可移除）----
g.setColor(new Color(255, 255, 255, 200))
g.fillRect(x0 - 6, y0 - 28, barLenPx + 12, 36)

// ---- 畫 bar --
g.setColor(BAR_COLOR)
g.fillRect(x0, y0, barLenPx, BAR_HEIGHT_PX)

// ---- 畫文字 ----
if (DRAW_SCALEBAR_TEXT) {
    g.setFont(new java.awt.Font(FONT_NAME, java.awt.Font.PLAIN, FONT_SIZE))
    g.setColor(TEXT_COLOR)

    String label
    if (SCALEBAR_UM >= 1000) {
        double mm = SCALEBAR_UM / 1000.0
        label = (mm == (int)mm) ? "${(int)mm} mm" : String.format("%.1f mm", mm)
    } else {
        label = "${(int)SCALEBAR_UM} µm"
    }

    g.drawString(label, x0, y0 - 10)
}


g.dispose()

// ================= ADD OVERLAY =================

def overlay = new BufferedImageOverlay(viewer, img)
viewer.getCustomOverlayLayers()
        .removeIf { it instanceof BufferedImageOverlay }
viewer.getCustomOverlayLayers().add(overlay)

// ================= HIDE QUPATH ANNOTATIONS =================

if (HIDE_QP_ANNOS) {
    def opts = viewer.getOverlayOptions()
    try {
        opts.setShowObjectPredicate(
                { po -> !(po instanceof PathAnnotationObject) }
                        as java.util.function.Predicate
        )
    } catch (Throwable e) {
        try { opts.setShowAnnotations(false) } catch (Throwable ignore) {}
    }
}

println "Overlay rendered. Colormap=${cmap.getName()}, maskDownsample=${MASK_DOWNSAMPLE}"
