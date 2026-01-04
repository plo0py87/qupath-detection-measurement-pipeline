// ==================== QuPath 0.6: Compartment-wise stats (Nucleus / Cytoplasm / Whole Cell) ====================
int    CHANNEL_INDEX   = 1            // 1=Channel 1; change if multi-channel
String CHANNEL_LABEL   = "Channel 1"  // Channel name to match in measurement list
// ==============================================================================================================

import qupath.lib.objects.PathAnnotationObject
import qupath.lib.objects.PathCellObject
import qupath.lib.roi.interfaces.ROI
import qupath.lib.gui.commands.Commands
import qupath.lib.analysis.features.ObjectMeasurements
import static qupath.lib.analysis.features.ObjectMeasurements.Measurements as OM

def imageData = getCurrentImageData()
def server    = imageData.getServer()
def cal       = server.getPixelCalibration()
def hier      = getCurrentHierarchy()

// ===== Image calibration (µm/px) =====
double umPerPxX = cal.getPixelWidthMicrons()
double umPerPxY = cal.getPixelHeightMicrons()
if (Double.isNaN(umPerPxX)) umPerPxX = 1.0
if (Double.isNaN(umPerPxY)) umPerPxY = 1.0
double um2_per_px = umPerPxX * umPerPxY

def annots = getAnnotationObjects()

// Reset selection
Commands.resetSelection(imageData)

// ===== Utilities =====
def findRegionMeanKey = { ml ->
    def names = ml.getNames() as List
    def cand = names.findAll { it?.toLowerCase()?.contains("mean") }
    if (cand.isEmpty()) return null
    def need1 = CHANNEL_LABEL.toLowerCase()
    def need2 = ("channel " + CHANNEL_INDEX).toLowerCase()
    def score = { String s ->
        def t = s.toLowerCase()
        int sc = 0
        if (t.contains(need1)) sc += 2
        if (t.contains(need2)) sc += 1
        if (t.contains("region") || t.contains("roi")) sc += 1
        return sc
    }
    return cand.max { score(it) }
}

def getCompartmentAreaPx = { PathCellObject c, String comp ->
    ROI rCell = c.getROI()
    ROI rNuc  = c.getNucleusROI()
    if (comp.equalsIgnoreCase("Nucleus"))
        return (rNuc != null) ? rNuc.getArea() : 0.0
    else if (comp.equalsIgnoreCase("Cytoplasm")) {
        if (rCell != null && rNuc != null) {
            double a = rCell.getArea() - rNuc.getArea()
            return (a > 0) ? a : 0.0
        }
        return 0.0
    } else // "Cell"
        return (rCell != null) ? rCell.getArea() : 0.0
}

def getCellMeanForComp = { PathCellObject c, String comp ->
    def mlC = c.getMeasurementList()
    def exactKey = "${comp}: ${CHANNEL_LABEL} mean".toString()
    double v = mlC.getOrDefault(exactKey, Double.NaN)
    if (!Double.isNaN(v)) return v
    def k = mlC.getNames().find{
        it.toLowerCase().contains(comp.toLowerCase()) &&
        it.toLowerCase().contains("mean") &&
        ( it.toLowerCase().contains(CHANNEL_LABEL.toLowerCase()) ||
          it.toLowerCase().contains(("channel " + CHANNEL_INDEX).toLowerCase()) )
    }
    return (k != null) ? mlC.getOrDefault(k, Double.NaN) : Double.NaN
}

// ===== Main loop =====
String[] COMPS = ["Nucleus", "Cytoplasm", "Cell"]
for (ann in annots) {
    def mlA = ann.getMeasurementList()

    // --- Area of annotation ---
    double area_px  = ann.getROI().getArea()
    double area_um2 = area_px * um2_per_px
    double area_mm2 = area_um2 / 1.0e6
    mlA.put("Region area (mm^2)", area_mm2)

    // --- Region mean intensity (compute if missing) ---
    def meanKeyA = findRegionMeanKey(mlA)
    if (meanKeyA == null) {
        ObjectMeasurements.addIntensityMeasurements(server, ann, 1.0, [OM.MEAN] as Set, [] as Set)
        meanKeyA = findRegionMeanKey(mlA)
    }
    double I_region = (meanKeyA != null) ? mlA.getOrDefault(meanKeyA, Double.NaN) : Double.NaN
    if (!Double.isNaN(I_region))
        mlA.put("Region mean (${CHANNEL_LABEL})", I_region)

    // --- Collect cells geometrically within the annotation ROI (descendants by location) ---
    // NOTE: This does not rely on hierarchy parent-child links; it finds all PathCellObject whose ROI intersects the annotation ROI.
    def cellsDesc = hier.getObjectsForROI(PathCellObject.class, ann.getROI())

    // --- Cell count & density: write our own measurements (do not rely on GUI 'Num Detections') ---
    int nAll = cellsDesc.size()
    mlA.put("Num Detections", (double)nAll)                  // store for export/use later
    mlA.put("Cell count (cells)", (double)nAll)
    mlA.put("Cell density (cells/mm^2)", (area_mm2 > 0) ? (nAll / area_mm2) : Double.NaN)

    // --- Accumulate compartment values ---
    def sum_IxA = [:].withDefault{0.0}
    def sum_A   = [:].withDefault{0.0}

    for (PathCellObject c in cellsDesc) {
        for (String comp : COMPS) {
            double a_px = getCompartmentAreaPx(c, comp)
            if (a_px <= 0) continue
            double v = getCellMeanForComp(c, comp)
            if (Double.isNaN(v)) continue
            double a_um2_c = a_px * um2_per_px
            sum_A[comp]   = sum_A[comp]   + a_um2_c
            sum_IxA[comp] = sum_IxA[comp] + v * a_um2_c
        }
    }

    // --- Write outputs per compartment ---
    COMPS.each { comp ->
        double A_in_um2   = sum_A[comp]
        double I_in_aw    = (A_in_um2 > 0) ? (sum_IxA[comp] / A_in_um2) : Double.NaN
        double A_out_um2  = Math.max(0.0, area_um2 - A_in_um2)
        double I_out_aw   = (!Double.isNaN(I_region) && A_out_um2 > 0) ? ((I_region*area_um2 - sum_IxA[comp]) / A_out_um2) : Double.NaN
        double frac_pct   = (area_um2 > 0) ? (100.0 * A_in_um2 / area_um2) : Double.NaN

        String tag = (comp.equals("Nucleus") ? "Nucleus" : comp.equals("Cytoplasm") ? "Cytoplasm" : "Whole")

        mlA.put("[${tag}] area (um^2)", A_in_um2)
        mlA.put("[${tag}] area fraction (%)", frac_pct)
        mlA.put("[${tag}] inside mean (area-weighted, ${CHANNEL_LABEL})", I_in_aw)
        mlA.put("[${tag}] outside mean (area-weighted, ${CHANNEL_LABEL})", I_out_aw)
    }
}

fireHierarchyUpdate()
print "Done: annotation stats written for Nucleus / Cytoplasm / Whole cell (with geometric cell count)."
