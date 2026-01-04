import qupath.lib.objects.*
import qupath.lib.roi.GeometryTools
import qupath.lib.roi.interfaces.ROI
import qupath.lib.analysis.features.ObjectMeasurements
import static qupath.lib.analysis.features.ObjectMeasurements.Measurements.*
import org.locationtech.jts.geom.Geometry

// =====================
// PARAMETERS
// =====================
int CHANNEL_INDEX = 1
String CHANNEL_LABEL = "Channel 1"
double DOWNSAMPLE = 1.0

// =====================
// SETUP
// =====================
def imageData = getCurrentImageData()
def server = imageData.getServer()
def hier = imageData.getHierarchy()
def cal = server.getPixelCalibration()

double umPerPx = cal.hasPixelSizeMicrons() ? cal.getAveragedPixelSizeMicrons() : 1.0
double um2PerPx = umPerPx * umPerPx

def annotations = getAnnotationObjects()

// =====================
// MAIN
// =====================
for (PathAnnotationObject ann : annotations) {

    ROI roi = ann.getROI()
    if (roi == null) continue

    def meas = ann.getMeasurementList()

    // --------------------------------------------------
    // Region area
    // --------------------------------------------------
    double regionArea_um2 = roi.getArea() * um2PerPx
    double regionArea_mm2 = regionArea_um2 / 1e6
    meas.put("Region area (mm^2)", regionArea_mm2)

    // --------------------------------------------------
    // Region mean (ensure exists)
    // --------------------------------------------------
    String regionMeanKey = null
    for (String k : meas.getNames()) {
        if (k == null) continue
        def kl = k.toLowerCase()
        if (kl.contains("mean") &&
            (kl.contains(CHANNEL_LABEL.toLowerCase()) || kl.contains(("channel " + CHANNEL_INDEX).toLowerCase()))) {
            regionMeanKey = k
            break
        }
    }

    if (regionMeanKey == null) {
        ObjectMeasurements.addIntensityMeasurements(server, ann, DOWNSAMPLE, [MEAN] as Set, [] as Set)
        for (String k : meas.getNames()) {
            if (k == null) continue
            def kl = k.toLowerCase()
            if (kl.contains("mean") &&
                (kl.contains(CHANNEL_LABEL.toLowerCase()) || kl.contains(("channel " + CHANNEL_INDEX).toLowerCase()))) {
                regionMeanKey = k
                break
            }
        }
    }

    double regionMean = regionMeanKey != null ? meas.get(regionMeanKey) : Double.NaN
    meas.put("Region mean (${CHANNEL_LABEL})", regionMean)

    // --------------------------------------------------
    // Cells in ROI
    // --------------------------------------------------
    def cells = hier.getObjectsForROI(PathCellObject.class, roi)
    meas.put("Cell count (cells)", cells.size())
    meas.put("Cell density (cells/mm^2)", regionArea_mm2 > 0 ? cells.size() / regionArea_mm2 : Double.NaN)

    // ==================================================
    // NON-EXCLUSIVE COMPARTMENTS
    //   internalComp: Nucleus / Cytoplasm / Cell
    //   outputLabel : Nucleus / Cytoplasm / Whole
    // ==================================================
    def sumArea = ["Nucleus":0.0, "Cytoplasm":0.0, "Cell":0.0]
    def sumIxA  = ["Nucleus":0.0, "Cytoplasm":0.0, "Cell":0.0]

    for (PathCellObject cell : cells) {

        ROI cellROI = cell.getROI()
        ROI nucROI  = cell.getNucleusROI()
        if (cellROI == null) continue

        double cellA = cellROI.getArea()
        double nucA  = nucROI != null ? nucROI.getArea() : 0.0
        double cytoA = Math.max(cellA - nucA, 0)

        def cellMeas = cell.getMeasurementList()

        for (String internalComp : ["Nucleus","Cytoplasm","Cell"]) {

            // key match: prefer exact "Comp: Channel 1 mean" if present, else fuzzy
            String key = null
            String exact = "${internalComp}: ${CHANNEL_LABEL} mean".toString()
            if (cellMeas.getNames().contains(exact)) key = exact
            if (key == null) {
                for (String k : cellMeas.getNames()) {
                    if (k == null) continue
                    def kl = k.toLowerCase()
                    if (kl.contains(internalComp.toLowerCase()) &&
                        kl.contains("mean") &&
                        (kl.contains(CHANNEL_LABEL.toLowerCase()) || kl.contains(("channel " + CHANNEL_INDEX).toLowerCase()))) {
                        key = k
                        break
                    }
                }
            }
            if (key == null) continue

            double meanVal = cellMeas.get(key)
            double areaPx = (internalComp == "Nucleus") ? nucA :
                            (internalComp == "Cytoplasm") ? cytoA : cellA

            double area_um2 = areaPx * um2PerPx
            sumArea[internalComp] += area_um2
            sumIxA[internalComp]  += meanVal * area_um2
        }
    }

    for (String internalComp : ["Nucleus","Cytoplasm","Cell"]) {

        String outLabel = (internalComp == "Cell") ? "Whole" : internalComp

        double Ain = sumArea[internalComp]
        double Iin = Ain > 0 ? sumIxA[internalComp] / Ain : Double.NaN
        double Aout = Math.max(regionArea_um2 - Ain, 0)
        double Iout = (Aout > 0 && !Double.isNaN(regionMean)) ?
                (regionMean * regionArea_um2 - sumIxA[internalComp]) / Aout : Double.NaN

        meas.put("[${outLabel}] area (um^2)", Ain)
        meas.put("[${outLabel}] area fraction (%)", regionArea_um2 > 0 ? 100*Ain/regionArea_um2 : Double.NaN)
        meas.put("[${outLabel}] inside mean (area-weighted, ${CHANNEL_LABEL})", Iin)
        meas.put("[${outLabel}] outside mean (area-weighted, ${CHANNEL_LABEL})", Iout)
    }

    // ==================================================
    // EXCLUSIVE ROI = parent ? direct children
    // ==================================================
    def children = ann.getChildObjects().findAll { it instanceof PathAnnotationObject }
    if (children.isEmpty()) continue

    Geometry parentG = GeometryTools.roiToGeometry(roi)
    Geometry childU = null
    for (PathAnnotationObject ch : children) {
        if (ch.getROI() == null) continue
        Geometry g = GeometryTools.roiToGeometry(ch.getROI())
        childU = (childU == null) ? g : childU.union(g)
    }
    if (childU == null) continue

    Geometry exclG = parentG.difference(childU)
    if (exclG.isEmpty()) continue

    ROI exclROI = GeometryTools.geometryToROI(exclG, roi.getImagePlane())
    double exclArea_um2 = exclROI.getArea() * um2PerPx
    double exclArea_mm2 = exclArea_um2 / 1e6
    meas.put("Exclusive region area (mm^2)", exclArea_mm2)

    // Exclusive region mean (temp annotation)
    def tempAnn = new PathAnnotationObject(exclROI)
    ObjectMeasurements.addIntensityMeasurements(server, tempAnn, DOWNSAMPLE, [MEAN] as Set, [] as Set)

    double exclMean = Double.NaN
    def tmpML = tempAnn.getMeasurementList()
    for (String k : tmpML.getNames()) {
        if (k == null) continue
        def kl = k.toLowerCase()
        if (kl.contains("mean") &&
            (kl.contains(CHANNEL_LABEL.toLowerCase()) || kl.contains(("channel " + CHANNEL_INDEX).toLowerCase()))) {
            exclMean = tmpML.get(k)
            break
        }
    }
    meas.put("Exclusive region mean (${CHANNEL_LABEL})", exclMean)

    def exclCells = hier.getObjectsForROI(PathCellObject.class, exclROI)
    meas.put("Exclusive Cell count (cells)", exclCells.size())
    meas.put("Exclusive Cell density (cells/mm^2)", exclArea_mm2 > 0 ? exclCells.size()/exclArea_mm2 : Double.NaN)

    // ==================================================
    // EXCLUSIVE COMPARTMENTS (same as above)
    // ==================================================
    def exSumArea = ["Nucleus":0.0, "Cytoplasm":0.0, "Cell":0.0]
    def exSumIxA  = ["Nucleus":0.0, "Cytoplasm":0.0, "Cell":0.0]

    for (PathCellObject cell : exclCells) {

        ROI cellROI = cell.getROI()
        ROI nucROI  = cell.getNucleusROI()
        if (cellROI == null) continue

        double cellA = cellROI.getArea()
        double nucA  = nucROI != null ? nucROI.getArea() : 0.0
        double cytoA = Math.max(cellA - nucA, 0)

        def cellMeas = cell.getMeasurementList()

        for (String internalComp : ["Nucleus","Cytoplasm","Cell"]) {

            String key = null
            String exact = "${internalComp}: ${CHANNEL_LABEL} mean".toString()
            if (cellMeas.getNames().contains(exact)) key = exact
            if (key == null) {
                for (String k : cellMeas.getNames()) {
                    if (k == null) continue
                    def kl = k.toLowerCase()
                    if (kl.contains(internalComp.toLowerCase()) &&
                        kl.contains("mean") &&
                        (kl.contains(CHANNEL_LABEL.toLowerCase()) || kl.contains(("channel " + CHANNEL_INDEX).toLowerCase()))) {
                        key = k
                        break
                    }
                }
            }
            if (key == null) continue

            double meanVal = cellMeas.get(key)
            double areaPx = (internalComp == "Nucleus") ? nucA :
                            (internalComp == "Cytoplasm") ? cytoA : cellA

            double area_um2 = areaPx * um2PerPx
            exSumArea[internalComp] += area_um2
            exSumIxA[internalComp]  += meanVal * area_um2
        }
    }

    for (String internalComp : ["Nucleus","Cytoplasm","Cell"]) {

        String outLabel = (internalComp == "Cell") ? "Whole" : internalComp

        double Ain = exSumArea[internalComp]
        double Iin = Ain > 0 ? exSumIxA[internalComp] / Ain : Double.NaN
        double Aout = Math.max(exclArea_um2 - Ain, 0)
        double Iout = (Aout > 0 && !Double.isNaN(exclMean)) ?
                (exclMean * exclArea_um2 - exSumIxA[internalComp]) / Aout : Double.NaN

        meas.put("Exclusive [${outLabel}] area (um^2)", Ain)
        meas.put("Exclusive [${outLabel}] area fraction (%)", exclArea_um2 > 0 ? 100*Ain/exclArea_um2 : Double.NaN)
        meas.put("Exclusive [${outLabel}] inside mean (area-weighted, ${CHANNEL_LABEL})", Iin)
        meas.put("Exclusive [${outLabel}] outside mean (area-weighted, ${CHANNEL_LABEL})", Iout)
    }
}

println "Done."
