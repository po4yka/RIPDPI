package com.poyka.ripdpi.data.awg

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A durably-persisted standalone AmneziaWG profile.
 *
 * Mirrors the stable-UUID profile-store shape of `DiagnosticProfileEntity`: a
 * String [id] primary key plus a serialized blob ([requestJson]) and an
 * [updatedAt] ordering column. The blob is a JSON-encoded
 * [AwgActivationRequest] **minus** its `profileId` -- the request's `profileId`
 * is supplied at re-activation time from this row's stable [id], so the saved
 * profile re-connects under the same opaque id every time.
 *
 * **Privacy invariant (network-fingerprint-privacy.md):** [id] is the opaque
 * `"awg-<UUID>"` form, minted once on first save and reused, NEVER derived from
 * the endpoint host/port. It is the value that flows into
 * `AwgActivationRequest.profileId` and thus into native runtime telemetry, so it
 * must not encode any network identifier. The endpoint host/port DO live inside
 * [requestJson] (the user's own server, pasted by the user -- allowed as user
 * config) but [requestJson] must never be logged or exported to telemetry.
 */
@Entity(tableName = "awg_profiles")
data class AwgProfileEntity(
    @PrimaryKey val id: String,
    val name: String,
    val requestJson: String,
    val updatedAt: Long,
)
