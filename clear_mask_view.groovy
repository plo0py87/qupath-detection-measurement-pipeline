import qupath.lib.gui.viewer.overlays.BufferedImageOverlay
import qupath.lib.objects.PathAnnotationObject

def viewer = getCurrentViewer()
if (viewer == null) {
    print "No active viewer."; return
}

// 1) 移除自訂遮罩 (白底 + 彩色統計)
def removed = viewer.getCustomOverlayLayers().removeIf{ it instanceof BufferedImageOverlay }
println removed ? "Removed custom overlay(s)." : "No BufferedImageOverlay found."

// 2) 還原 annotation 顯示
def opts = viewer.getOverlayOptions()
try {
    opts.setShowObjectPredicate(null)   // 清除之前的「隱藏 annotation」條件
    println "Annotations restored (via predicate reset)."
} catch (Throwable ignore) {
    try { opts.setShowAnnotations(true) }   catch (Throwable e) {}
    try { opts.setFillAnnotations(true) }   catch (Throwable e) {}
    try { opts.setShowNames(true) }         catch (Throwable e) {}
    try { opts.setShowMeasurements(true) }  catch (Throwable e) {}
    println "Annotations restored (via legacy flags)."
}

println "Viewer restored to default (image + annotations visible)."
