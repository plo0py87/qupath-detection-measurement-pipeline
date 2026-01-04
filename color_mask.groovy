import qupath.lib.gui.viewer.overlays.BufferedImageOverlay
import java.awt.image.BufferedImage
import java.awt.Graphics2D
import java.awt.BasicStroke
import java.awt.RenderingHints
import java.awt.Color
import java.awt.geom.AffineTransform
import qupath.lib.objects.PathAnnotationObject
import qupath.lib.color.ColorMaps

// ================= USER PARAMETERS =================

// Viewer 縮放倍率
final double TARGET_DOWNSAMPLE = 5.0

// Measurement 欄位
final String MEAS_NAME = "[Whole] inside mean (area-weighted, Channel 1)"

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

viewer.setDownsampleFactor(TARGET_DOWNSAMPLE)
viewer.setCenterPixelLocation(centerX, centerY)
viewer.repaint()


println "Viewer centered at (${(int)centerX}, ${(int)centerY}), downsample=${TARGET_DOWNSAMPLE}"

// ================= SEE COLOR MAPS =================

def cmapMap = ColorMaps.getColorMaps()
def cmap = cmapMap.get(COLORMAP_NAME)
if (cmap == null) {
    println "Colormap '${COLORMAP_NAME}' not found, using default"
    cmap = ColorMaps.getDefaultColorMap()
}

// ================= MEASUREMENT RANGE =================

def measOf = { PathAnnotationObject a ->
    a.getMeasurementList().getOrDefault(MEAS_NAME, Double.NaN) as double
}

def vals = allAnns.collect { measOf(it) }
        .findAll { Double.isFinite(it) }

if (vals.isEmpty()) {
    println "No valid measurement values"
    return
}

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

println "Color scale: [${vmin}, ${vmax}]"

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
