package dev.healthhub.core.healthconnect

import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.BloodPressureRecord
import androidx.health.connect.client.records.BodyFatRecord
import androidx.health.connect.client.records.CyclingPedalingCadenceRecord
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.ElevationGainedRecord
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.HeartRateVariabilityRmssdRecord
import androidx.health.connect.client.records.OxygenSaturationRecord
import androidx.health.connect.client.records.PowerRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.RestingHeartRateRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.SpeedRecord
import androidx.health.connect.client.records.StepsCadenceRecord
import androidx.health.connect.client.records.TotalCaloriesBurnedRecord
import androidx.health.connect.client.records.WeightRecord
import kotlin.reflect.KClass

/**
 * Which Health Connect record types this app reads, grouped by the feature that needs them.
 *
 * The grouping is not tidiness — it is Constitution Principle IV made mechanical. A permission
 * is requested because a *domain the athlete switched on* names the record type, so a fresh
 * install asks for workout data and nothing else. Asking for sleep, heart-rate variability and
 * body weight at install time, before a single screen exists that uses them, is how an athlete
 * decides to grant nothing at all.
 *
 * Adding a type is one entry here. The sync engine reads it, the permission request includes
 * it once its domain is on, and [HealthRecordRegistry.notIngested] is the list that keeps
 * everything else from being silently absent (Principle VI).
 */
enum class HealthDomain(
    val slug: String,
    val label: String,
    /** Shown beside the switch. Says what the athlete gets, not what the app reads. */
    val purpose: String,
) {
    WORKOUTS(
        slug = "workouts",
        label = "Workouts",
        purpose = "Sessions, heart rate, speed, power, cadence, distance, elevation and calories.",
    ),
    SLEEP(
        slug = "sleep",
        label = "Sleep",
        purpose = "Nights and their stages, for the sleep trend and the readiness score.",
    ),
    RECOVERY(
        slug = "recovery",
        label = "Recovery",
        purpose = "Heart-rate variability, resting heart rate and blood oxygen.",
    ),
    BODY(
        slug = "body",
        label = "Body composition",
        purpose = "Weight and body fat.",
    ),
    VITALS(
        slug = "vitals",
        label = "Blood pressure",
        purpose = "Systolic and diastolic readings.",
    ),
    ;

    companion object {
        private val bySlug = entries.associateBy(HealthDomain::slug)
        fun fromSlug(slug: String): HealthDomain? = bySlug[slug]

        /**
         * The domain that cannot be switched off: it is the product. Everything else is opt-in,
         * which is the whole point of the split.
         */
        val ALWAYS_ON = WORKOUTS
    }
}

/**
 * One record type the app knows how to ingest.
 *
 * [measurementKind] is the `kind` slug it uploads under on
 * `POST /api/health-records/measurements`, and it is null for the two types that are not scalar
 * measurements — an exercise session and a night of sleep each have their own shape and their
 * own route. The edge deliberately does not allow-list these slugs, so a type added here needs
 * no Worker deploy (see `contracts/api.md`).
 */
data class RegisteredRecord(
    val recordType: KClass<out Record>,
    val domain: HealthDomain,
    val measurementKind: String? = null,
    /** The unit the value is uploaded in. Stored verbatim; the edge does not interpret it. */
    val unit: String? = null,
) {
    val permission: String get() = HealthPermission.getReadPermission(recordType)

    /** What the sync report calls this type. */
    val typeName: String get() = recordType.simpleName?.removeSuffix("Record") ?: "Unknown"
}

/** A Health Connect type this app does not read, and why. Named rather than left silent. */
data class UnmodelledRecord(val typeName: String, val reason: String)

object HealthRecordRegistry {

    /**
     * Everything the app ingests.
     *
     * The workout half is unchanged from the first slice — it is listed here rather than in a
     * second place, so `WORKOUT_RECORD_TYPES` and this table cannot drift apart.
     */
    val entries: List<RegisteredRecord> = listOf(
        RegisteredRecord(ExerciseSessionRecord::class, HealthDomain.WORKOUTS),
        RegisteredRecord(HeartRateRecord::class, HealthDomain.WORKOUTS),
        RegisteredRecord(SpeedRecord::class, HealthDomain.WORKOUTS),
        RegisteredRecord(PowerRecord::class, HealthDomain.WORKOUTS),
        RegisteredRecord(CyclingPedalingCadenceRecord::class, HealthDomain.WORKOUTS),
        RegisteredRecord(StepsCadenceRecord::class, HealthDomain.WORKOUTS),
        RegisteredRecord(DistanceRecord::class, HealthDomain.WORKOUTS),
        RegisteredRecord(ElevationGainedRecord::class, HealthDomain.WORKOUTS),
        RegisteredRecord(TotalCaloriesBurnedRecord::class, HealthDomain.WORKOUTS),

        RegisteredRecord(SleepSessionRecord::class, HealthDomain.SLEEP),

        RegisteredRecord(
            HeartRateVariabilityRmssdRecord::class,
            HealthDomain.RECOVERY,
            measurementKind = Kind.HRV_RMSSD,
            unit = "ms",
        ),
        RegisteredRecord(
            RestingHeartRateRecord::class,
            HealthDomain.RECOVERY,
            measurementKind = Kind.RESTING_HEART_RATE,
            unit = "bpm",
        ),
        RegisteredRecord(
            OxygenSaturationRecord::class,
            HealthDomain.RECOVERY,
            measurementKind = Kind.OXYGEN_SATURATION,
            unit = "%",
        ),

        RegisteredRecord(
            WeightRecord::class,
            HealthDomain.BODY,
            measurementKind = Kind.WEIGHT,
            unit = "kg",
        ),
        RegisteredRecord(
            BodyFatRecord::class,
            HealthDomain.BODY,
            measurementKind = Kind.BODY_FAT,
            unit = "%",
        ),

        RegisteredRecord(
            BloodPressureRecord::class,
            HealthDomain.VITALS,
            measurementKind = Kind.BLOOD_PRESSURE,
            unit = "mmHg",
        ),
    )

    /** The `kind` slugs, in one place, because both the uploader and the screens name them. */
    object Kind {
        const val HRV_RMSSD = "hrv_rmssd"
        const val RESTING_HEART_RATE = "resting_heart_rate"
        const val OXYGEN_SATURATION = "oxygen_saturation"
        const val WEIGHT = "weight"
        const val BODY_FAT = "body_fat"

        /** value is systolic, secondaryValue is diastolic. The edge stores two numbers. */
        const val BLOOD_PRESSURE = "blood_pressure"
    }

    fun forDomains(domains: Set<HealthDomain>): List<RegisteredRecord> =
        entries.filter { it.domain in domains }

    /** The scalar types in these domains — the ones that upload as measurements. */
    fun measurementsIn(domains: Set<HealthDomain>): List<RegisteredRecord> =
        forDomains(domains).filter { it.measurementKind != null }

    /**
     * Health Connect types that exist and that this app does not read.
     *
     * This list is the whole of Principle VI's "fail loudly" for data the app never asked for.
     * The alternative is silence: an athlete whose phone is full of nutrition and body
     * temperature readings sees a HealthHub that shows none of it and says nothing about why,
     * and cannot tell "not supported" from "broken". Every sync report names these, and the
     * health screen lists them where they can actually be read.
     *
     * A type moves out of this list by gaining an entry in [entries]. Nothing else changes.
     */
    val notIngested: List<UnmodelledRecord> = listOf(
        UnmodelledRecord("ActiveCaloriesBurned", "total calories is ingested instead"),
        UnmodelledRecord("BasalBodyTemperature", "no screen models it"),
        UnmodelledRecord("BasalMetabolicRate", "no screen models it"),
        UnmodelledRecord("BloodGlucose", "clinical; deliberately not requested"),
        UnmodelledRecord("BodyTemperature", "no screen models it"),
        UnmodelledRecord("BodyWaterMass", "no screen models it"),
        UnmodelledRecord("BoneMass", "no screen models it"),
        UnmodelledRecord("CervicalMucus", "reproductive health; deliberately not requested"),
        UnmodelledRecord("FloorsClimbed", "elevation gained is ingested instead"),
        UnmodelledRecord("Height", "no screen models it"),
        UnmodelledRecord("Hydration", "no screen models it"),
        UnmodelledRecord("IntermenstrualBleeding", "reproductive health; deliberately not requested"),
        UnmodelledRecord("LeanBodyMass", "no screen models it"),
        UnmodelledRecord("MenstruationFlow", "reproductive health; deliberately not requested"),
        UnmodelledRecord("MenstruationPeriod", "reproductive health; deliberately not requested"),
        UnmodelledRecord("Nutrition", "no screen models it"),
        UnmodelledRecord("OvulationTest", "reproductive health; deliberately not requested"),
        UnmodelledRecord("PlannedExerciseSession", "a plan is not a recording"),
        UnmodelledRecord("RespiratoryRate", "no screen models it"),
        UnmodelledRecord("SexualActivity", "deliberately not requested"),
        UnmodelledRecord("SkinTemperature", "no screen models it"),
        UnmodelledRecord("Steps", "step counts are not a workout; cadence is ingested"),
        UnmodelledRecord("Vo2Max", "estimated by the recording app, not measured"),
        UnmodelledRecord("WheelchairPushes", "no screen models it"),
    )
}
