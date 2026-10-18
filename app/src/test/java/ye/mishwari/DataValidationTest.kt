package ye.mishwari

import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import ye.mishwari.data.DataValidation
import ye.mishwari.data.Errand
import ye.mishwari.data.Place

class DataValidationTest {
    @Test
    fun errandTitleMustNotBeBlank() {
        assertThrows(IllegalArgumentException::class.java) {
            DataValidation.validateErrand(Errand(title = "  "))
        }
        DataValidation.validateErrand(Errand(title = "شراء دواء"))
    }

    @Test
    fun placeCoordinatesMustBePairedAndInRange() {
        assertThrows(IllegalArgumentException::class.java) {
            DataValidation.validatePlace(Place(name = "منزل", latitude = 15.0))
        }
        assertThrows(IllegalArgumentException::class.java) {
            DataValidation.validatePlace(Place(name = "منزل", latitude = 91.0, longitude = 44.0))
        }
        DataValidation.validatePlace(Place(name = "منزل", latitude = 15.0, longitude = 44.0))
    }

    @Test
    fun radiusMustBeBetweenOneHundredAndTwentyThousandMeters() {
        for (radius in listOf(99.9f, 20_000.1f, Float.NaN, Float.POSITIVE_INFINITY)) {
            assertThrows(IllegalArgumentException::class.java) {
                DataValidation.validatePlace(Place(name = "منزل", radiusMeters = radius))
            }
        }
        DataValidation.validatePlace(Place(name = "منزل", radiusMeters = 100f))
        DataValidation.validatePlace(Place(name = "منزل", radiusMeters = 20_000f))
    }

    @Test
    fun hierarchyAllowsValidParentChildAndRejectsMissingParentAndCycles() {
        val root = Place(id = "root", name = "محافظة")
        val child = Place(id = "child", name = "مديرية", parentId = "root")
        DataValidation.validateHierarchy(listOf(root, child))
        assertTrue(child.parentId == root.id)

        assertThrows(IllegalArgumentException::class.java) {
            DataValidation.validateHierarchy(listOf(child))
        }
        val a = Place(id = "a", name = "أ", parentId = "b")
        val b = Place(id = "b", name = "ب", parentId = "a")
        assertThrows(IllegalArgumentException::class.java) {
            DataValidation.validateHierarchy(listOf(a, b))
        }
    }
}
