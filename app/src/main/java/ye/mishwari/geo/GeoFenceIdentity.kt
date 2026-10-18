package ye.mishwari.geo

import java.security.MessageDigest

/**
 * Stable, bounded registration identity that changes when a place's fence geometry changes.
 * The receiver can resolve it by comparing requestId(candidate) over its current candidates;
 * no original place ID is embedded in the request ID, keeping it below Google's 100-char limit.
 */
internal object GeoFenceIdentity {
    private const val PREFIX = "mwgeo:v1:"
    private const val SIGNATURE_BYTES = 12

    /** Use this exact ID when registering a candidate and when resolving incoming event IDs. */
    fun requestId(candidate: GeoPolicy.Candidate): String = requestId(
        candidate.id,
        candidate.latitude,
        candidate.longitude,
        candidate.radiusMeters,
    )

    fun requestId(placeId: String, latitude: Double?, longitude: Double?, radiusMeters: Float): String =
        "$PREFIX${sha256(placeId).take(64)}:${geometrySignature(latitude, longitude, radiusMeters)}"

    /** Stable per-place geometry key for resetting occupied state only when that fence moves. */
    fun geometrySignature(candidate: GeoPolicy.Candidate): String =
        geometrySignature(candidate.latitude, candidate.longitude, candidate.radiusMeters)

    fun geometrySignature(latitude: Double?, longitude: Double?, radiusMeters: Float): String {
        val canonical = "${latitude?.toString() ?: "null"}\u0000${longitude?.toString() ?: "null"}\u0000${radiusMeters.toString()}"
        return sha256(canonical).take(SIGNATURE_BYTES * 2)
    }

    fun matches(requestId: String, candidate: GeoPolicy.Candidate): Boolean = requestId == requestId(candidate)

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
}
