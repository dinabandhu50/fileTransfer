package org.mitre.synthea.export;

import com.google.gson.JsonObject;
import org.apache.avro.Schema;
import org.apache.avro.generic.GenericData;
import org.mitre.synthea.helpers.Config;
import org.mitre.synthea.helpers.ExportLogHelper;
import org.mitre.synthea.helpers.ParquetWriter;
import org.mitre.synthea.helpers.Utilities;
import org.mitre.synthea.world.agents.Person;
import org.apache.hadoop.fs.Path;
import org.mitre.synthea.world.concepts.HealthRecord.Code;
import org.mitre.synthea.world.concepts.HealthRecord.Encounter;
import org.mitre.synthea.world.concepts.HealthRecord.Entry;
import org.mitre.synthea.world.concepts.HealthRecord.Medication;
import org.mitre.synthea.world.concepts.HealthRecord.Observation;
import org.mitre.synthea.world.concepts.HealthRecord.Procedure;

import static org.mitre.synthea.export.ExportHelper.dateFromTimestamp;
import static org.mitre.synthea.export.ExportHelper.iso8601Timestamp;
import static org.mitre.synthea.helpers.Utilities.writeLine;

import java.util.Date;
import java.sql.Timestamp;
import java.io.File;
import java.io.FileWriter;
import java.io.*;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.ArrayBlockingQueue;

public class ParquetExporter {
    /**
     * List of patient maintained in memory
     */
    private Queue<Person> patientQueue;

    /**
     * Output folder
     */
    File output;
    /**
     * Metadata folder
     */
    private File metadataFolder;
    /**
     * System-dependent string for a line break. (\n on Mac, *nix, \r\n on Windows)
     */
    private static final String NEWLINE = System.lineSeparator();

    /**
     * List of event types to be exported.
     * This enum is used to evaluate what will go in to each list (records list, schema list etc)
     */
    private enum ExportEvents{
        patient,
        medicationrequest,
        encounter,
        condition,
        observation,
        procedure,
        measure,
        state
    }

    /**
     * Whitelist of state to output to state files
     */
    private static final List<String> ATTRIBUTE_WHITELIST = Arrays.asList(new String[] {
            "address",
            "adherence probability",
            "age_18_50_before_delay",
            "age_18_50_after_delay",
            "age_50_plus_before_delay",
            "age_50_plus_after_delay",
            "alcoholic",
            "alcoholic_history",
            "alk",
            "asthma_type",
            "atopic",
            "atrial_fibrillation",
            "atrial_fibrillation_risk",
            "birth_type",
            "cardio_risk",
            "cause_of_death",
            "colorectal_cancer_stage",
            "coronary_heart_disease",
            "cr_chemo_count",
            "diabetes",
            "diabetes_amputation_necessary",
            "diabetes_severity",
            "diabetic_eye_damage",
            "diabetic_nerve_damage",
            "education",
            "egfr",
            "first_language",
            "gender",
            "Hunt-Hess_Grade",
            "homeless",
            "homelessness_category",
            "hypertension",
            "income",
            "infertile",
            "instances_of_homelessness",
            "is_sah",
            "kras",
            "Lung Cancer Type",
            "lung_cancer",
            "lung_cancer_nondiagnosis_counter",
            "macular_edema",
            "nephropathy",
            "neuropathy",
            "nonproliferative_retinopathy",
            "number_of_children",
            "occupation_level",
            "onset_age_eighteen_to_fifty_after_delay",
            "onset_age_eighteen_to_fifty_before_delay",
            "onset_age_fifty_plus_after_delay",
            "onset_age_fifty_plus_before_delay",
            "opioid_addiction",
            "osteoporosis",
            "outgrew_food_allergies",
            "pd1",
            "prediabetes",
            "quit alcoholism age",
            "quit alcoholism probability",
            "quit smoking age",
            "quit smoking probability",
            "retinopathy",
            "RH_NEG",
            "sah_suspect",
            "sexual_orientation",
            "sexually_active",
            "smoker",
            "smoker_history",
            "socioeconomic_category",
            "stroke_history",
            "stroke_points",
            "stroke_risk",
            "veteran"
    });

    /**
     * Schemas used for export
     */
    private Map<ExportEvents, Schema> schemas;

    /**
     * Counts of generated records for logger
     */
    private Map<String, Integer> logCounts;

    private ParquetExporter() {
        // Size of queue is determined based on config value
        int listSize = new Integer(Config.get("exporter.parquet.patient_queue_size", "1"));
        patientQueue = new ArrayBlockingQueue<>(listSize);
        schemas = new HashMap<>();
        try {
            output = Exporter.getOutputFolder("parquet", null);
            output.mkdirs();
            for (ExportEvents eventType: ExportEvents.values()) {
                schemas.put(eventType, new Schema.Parser().parse(new String(
                        Files.readAllBytes(Paths.get(ClassLoader.getSystemClassLoader()
                                .getResource("avro_schemas/" + eventType + ".avsc").toURI())))));
            }

            metadataFolder = Exporter.getOutputFolder("metadata", null);
            metadataFolder.mkdirs();
        } catch (IOException ioe) {
            ioe.printStackTrace();
        } catch (URISyntaxException uris) {
            uris.printStackTrace();
        }
    }

    /**
     *  Thread safe singleton pattern adopted from
     *  https://stackoverflow.com/questions/7048198/thread-safe-singletons-in-java
     */
    private static class SingletonHolder {
        /**
         * Singleton instance of the ParquetExporter.
         */
        private static final ParquetExporter instance = new ParquetExporter();
    }

    /**
     * Get the current instance of the ParquetExporter.
     * @return the current instance of the ParquetExporter.
     */
    public static ParquetExporter getInstance() {
        return SingletonHolder.instance;
    }


    public void export(Person person, long time) throws IOException {
        synchronized (patientQueue) {
            if (!patientQueue.offer(person)) {
                // queue is full, write to disk
                // System.out.println("Patient queue size at write: " + patientQueue.size());
                writeRecords(patientQueue, time);
                // System.out.println("Size of queued list: " + patientQueue.size());
                // System.out.println("Size (in memory) of queued list: " + RamUsageEstimator.sizeOf(patientQueue) + " bytes");
            }
        }
    }

    public void postCompletionExport(long time) throws IOException {
        if (patientQueue.peek() != null) {
            writeRecords(patientQueue, time);
        }
    }


    private void writeRecords(Queue<Person> patientQueue, long time) throws IOException {

        Map<ExportEvents, Path> outputFiles = new HashMap<>();
        Map<ExportEvents, List<GenericData.Record>> outputRecordLists = new HashMap<>();
        for (ExportEvents eventType: ExportEvents.values()) {
            outputFiles.put(eventType, new Path(output.toPath()
                    .toString() +"/" + eventType + "/" + eventType+ "-"
                    + patientQueue.peek().attributes.get(Person.ID) + ".parquet"));

            outputRecordLists.put(eventType, new ArrayList<>());
        }
        FileWriter metadataFileWriter = new FileWriter(metadataFolder
                .toPath().resolve("meta-" + patientQueue.peek().attributes.get(Person.ID) + ".txt").toFile());

        while (patientQueue.peek() != null) {
            Person p = patientQueue.poll();
            String personId = p.attributes.get(Person.ID).toString();
            outputRecordLists.get(ExportEvents.patient).add(buildPatientRecord(p, time));

            for (Encounter encounter: p.record.encounters) {
                String encounterId = UUID.randomUUID().toString();
                outputRecordLists.get(ExportEvents.encounter).add(buildEncounterRecord(encounterId, encounter, personId));
                for (Medication medication: encounter.medications) {
                    outputRecordLists.get(ExportEvents.medicationrequest).add(buildMedicationRecord(medication,
                            personId, encounter, encounterId, time));
                }
                for (Entry condition: encounter.conditions) {
                    outputRecordLists.get(ExportEvents.condition).add(buildConditionRecord(personId, condition,
                            p.ageInYears(condition.start)));
                }
                for (Observation observation: encounter.observations) {
                    buildObservationList(outputRecordLists.get(ExportEvents.observation), personId, encounterId, observation);
                }
                for (Procedure procedure: encounter.procedures) {
                    outputRecordLists.get(ExportEvents.procedure)
                            .add(buildProcedureRecord(personId, encounterId, procedure));
                }

            }
            Map<Integer, Double> qalys = (Map<Integer, Double>) p.attributes.get("QALY");
            Map<Integer, Double> dalys = (Map<Integer, Double>) p.attributes.get("DALY");
            Map<Integer, Double> qols = (Map<Integer, Double>) p.attributes.get("QOL");

           for (Integer year: qols.keySet()) {
                outputRecordLists.get(ExportEvents.measure)
                        .add(buildQualityOfLifeRecord(personId, year, qols.get(year), qalys.get(year), dalys.get(year)));
            }

            for (Map.Entry<String, Object> attr: p.attributes.entrySet()) {
                if (ATTRIBUTE_WHITELIST.contains(attr.getKey())) {
                    outputRecordLists.get(ExportEvents.state)
                        .add(buildAttributeRecord(personId, attr.getKey(), String.valueOf(attr.getValue())));
                }
            }
        }
        this.logCounts = new HashMap<>();
        for (ExportEvents eventType: ExportEvents.values()) {
            System.out.println("Number of " + eventType + " records written: " + outputRecordLists.get(eventType).size());
            new ParquetWriter().writeToParquet(outputRecordLists.get(eventType), outputFiles.get(eventType), schemas.get(eventType));
            this.logCounts.put(eventType.toString(), outputRecordLists.get(eventType).size());
            Timestamp ts = new Timestamp(new Date().getTime());
            StringBuilder sb = new StringBuilder();
            sb.append(eventType.toString()).append(',');
            sb.append(outputFiles.get(eventType)).append(',');
            sb.append(ts.toString()).append(NEWLINE);
            writeLine(sb.toString(), metadataFileWriter);
        }
        metadataFileWriter.flush();
        metadataFileWriter.close();
        ExportLogHelper.sendUpdate(this.logCounts);
    }

    /**
     * Build a single patient record in avro.
     * @param p Person object
     * @param time Current generator time in milliseconds
     * @return The built patient record
     */
    private GenericData.Record buildPatientRecord(Person p, long time) {
        final List<String> PATIENT_ATTRIBUTES = Arrays.asList(new String[] {
                "race",
                "gender",
                "zip",
                "address",
                "city",
                "socioeconomic_category",
                "alcoholic",
                "alcoholic_history",
                "asthma_type",
                "birth_type",
                "cause_of_death",
                "coronary_heart_disease",
                "deceased",
                "diabetes",
                "first_language",
                "homeless",
                "homelessness_category",
                "instances_of_homelessness",
                "infertile",
                "hypertension",
                "lung_cancer",
                "opioid_addiction",
                "osteoporosis",
                "prediabetes",
                "sexual_orientation",
                "sexually_active",
                "smoker",
                "smoker_history",
                "veteran"
        });
        GenericData.Record patientRecord = new GenericData.Record(schemas.get(ExportEvents.patient));

        patientRecord.put("subject", p.attributes.get(Person.ID));
        patientRecord.put("name", p.attributes.get(Person.NAME));
        patientRecord.put("date_of_birth", dateFromTimestamp((long) p.attributes.get(Person.BIRTHDATE)));
        if (!p.alive(time)) {
            patientRecord.put("date_of_death", dateFromTimestamp(p.record.death));
        } else {
            patientRecord.put("date_of_death", "");
        }

        for (String attr: PATIENT_ATTRIBUTES) {
            patientRecord.put(attr, String.valueOf(p.attributes.getOrDefault(attr, "")));
        }

        return patientRecord;
    }

    /**
     * Build a single medicationrequest record in avro
     * @param medication Medication
     * @param personId Person ID related to medicationrequest
     * @param encounter Encounter related to medicationrequest
     * @param encounterId Encounter ID related to medicationrequest
     * @param time Current generator time
     * @return The build medicationrequest record
     */
    private GenericData.Record buildMedicationRecord(Medication medication,
                                                     String personId, Encounter encounter,
                                                     String encounterId, long time) {

        GenericData.Record medicationRecord = new GenericData.Record(schemas.get(ExportEvents.medicationrequest));
        medicationRecord.put("identifier", UUID.randomUUID().toString());
        medicationRecord.put("subject", personId);
        String providerId = encounter.provider != null ? encounter.provider.id : "";
        medicationRecord.put("practitioner", providerId);
        medicationRecord.put("encounter", encounterId);
        String medicationName = medication.name != null ? medication.name : "";
        medicationRecord.put("name", medicationName);
        medicationRecord.put("type", medication.type);
        medicationRecord.put("start", dateFromTimestamp(medication.start));

        long stop = medication.stop;
        if (stop == 0L) {
            stop = time;
            medicationRecord.put("end", "");
        } else {
            medicationRecord.put("end", dateFromTimestamp(medication.stop));
        }
        long medicationDuration = stop - medication.start;
        Code coding = medication.codes.get(0);
        medicationRecord.put("code", coding.code);
        medicationRecord.put("display", coding.display);
        medicationRecord.put("system", coding.system);
        BigDecimal cost = medication.cost();
        medicationRecord.put("cost", String.format(Locale.US, "%.2f", cost));
        long dispenses = 1;
        if (medication.prescriptionDetails != null
                && medication.prescriptionDetails.has("refills")) {
            dispenses = medication.prescriptionDetails.get("refills").getAsInt();
        } else if (medication.prescriptionDetails != null
                && medication.prescriptionDetails.has("duration")) {
            JsonObject duration = medication.prescriptionDetails.getAsJsonObject("duration");

            long quantity = duration.get("quantity").getAsLong();
            String unit = duration.get("unit").getAsString();
            long durationMs = Utilities.convertTime(unit, quantity);
            dispenses = medicationDuration / durationMs;
        } else {
            // assume 1 refill / month
            long durationMs = Utilities.convertTime("months", 1);
            dispenses = medicationDuration / durationMs;
        }

        if (dispenses < 1) {
            // integer division could leave us with 0,
            // ex. if the active time (start->stop) is less than the provided duration
            // or less than a month if no duration provided
            dispenses = 1;
        }
        medicationRecord.put("dispenses", dispenses);
        BigDecimal totalCost = cost
                .multiply(BigDecimal.valueOf(dispenses))
                .setScale(2, RoundingMode.DOWN); // truncate to 2 decimal places
        medicationRecord.put("total_cost", String.format(Locale.US, "%.2f", totalCost));
        if (medication.reasons.isEmpty()) {
            medicationRecord.put("reason_code", "");
            medicationRecord.put("reason_description", "");
        } else {
            Code reason = medication.reasons.get(0);
            medicationRecord.put("reason_code", reason.code);
            medicationRecord.put("reason_description", reason.display);
        }

        return medicationRecord;
    }

    /**
     * Build a single encounter record in avro
     * @param encounterId The encounter Id
     * @param encounter The encounter
     * @param personId Person ID related to the encounter
     * @return The build encounter record
     */
    private GenericData.Record buildEncounterRecord(String encounterId,
                                                    Encounter encounter, String personId) {
        GenericData.Record encounterRecord = new GenericData.Record(schemas.get(ExportEvents.encounter));

        encounterRecord.put("identifier", encounterId);
        encounterRecord.put("subject", personId);
        String providerId = encounter.provider != null ? encounter.provider.id : "";
        encounterRecord.put("practitioner", providerId);
        String encounterName = encounter.name != null ? encounter.name : "";
        encounterRecord.put("name", encounterName);
        String type = encounter.type != null ? encounter.type : "";
        encounterRecord.put("type", type);
        encounterRecord.put("start", iso8601Timestamp(encounter.start));
        if (encounter.stop != 0L) {
            encounterRecord.put("end", iso8601Timestamp(encounter.stop));
        } else {
            encounterRecord.put("end", "");
        }
        Code coding = encounter.codes.get(0);
        encounterRecord.put("code", coding.code);
        encounterRecord.put("display", coding.display);
        encounterRecord.put("system", coding.system);

        return encounterRecord;
    }

    /**
     * Build a single condition record in avro
     * @param personId The person ID related to the condition
     * @param condition The condition
     * @return The built condition record
     */
    private GenericData.Record buildConditionRecord(String personId, Entry condition, int onsetAge) {
        GenericData.Record conditionRecord = new GenericData.Record(schemas.get(ExportEvents.condition));

        conditionRecord.put("subject", personId);
        conditionRecord.put("onsetage", onsetAge);
        String conditionName = condition.name != null ? condition.name : "";
        conditionRecord.put("name", conditionName);
        conditionRecord.put("type", condition.type);
        conditionRecord.put("onsetdatetime", iso8601Timestamp(condition.start));
        if (condition.stop != 0L) {
            conditionRecord.put("abatementdatetime", iso8601Timestamp(condition.stop));
        } else {
            conditionRecord.put("abatementdatetime", "");
        }
        Code coding = condition.codes.get(0);
        conditionRecord.put("code", coding.code);
        conditionRecord.put("display", coding.display);
        conditionRecord.put("system", coding.system);

        return conditionRecord;
    }

    /**
     * Create a single Observation record in avro
     * @param personId The person ID related to the observation
     * @param encounterId The encounter ID related to the observation
     * @param observation The Observation
     * @return The generated observation record
     */
    private GenericData.Record buildObservationRecord(String personId, String encounterId,
                                                      Observation observation) {

        GenericData.Record observationRecord = new GenericData.Record(schemas.get(ExportEvents.observation));

        observationRecord.put("subject", personId);
        observationRecord.put("encounter", encounterId);
        String observationName = observation.name != null ? observation.name : "";
        observationRecord.put("name", observationName);
        String type = ExportHelper.getObservationType(observation);
        if (type != null) {
            observationRecord.put("type", type);
        } else {
            observationRecord.put("type", "");
        }
        observationRecord.put("start", dateFromTimestamp(observation.start));
        String value = ExportHelper.getObservationValue(observation);
        if (value != null) {
            observationRecord.put("value", value);
        } else {
            observationRecord.put("value", "");
        }
        String unit = observation.unit != null ? observation.unit : "";
        observationRecord.put("unit", unit);
        Code coding = observation.codes.get(0);
        observationRecord.put("code", coding.code);
        observationRecord.put("display", coding.display);
        observationRecord.put("system", coding.system);

        return observationRecord;
    }

    /**
     * Create a single Procedure record in avro
     * @param personId The person ID related to the procedure
     * @param encounterId The encounter ID related to the procedure
     * @param procedure The procedure
     * @return The created procedure record
     */
    private GenericData.Record buildProcedureRecord(String personId, String encounterId,
                                                    Procedure procedure) {
        GenericData.Record procedureRecord = new GenericData.Record(schemas.get(ExportEvents.procedure));

        procedureRecord.put("date", dateFromTimestamp(procedure.start));
        procedureRecord.put("subject", personId);
        procedureRecord.put("encounter", encounterId);
        Code coding = procedure.codes.get(0);
        procedureRecord.put("code", coding.code);
        procedureRecord.put("display", coding.display);
        procedureRecord.put("cost", String.format(Locale.US, "%.2f", procedure.cost()));

        if (procedure.reasons.isEmpty()) {
            procedureRecord.put("reason_code", "");
            procedureRecord.put("reason_description", "");
        } else {
            Code reason = procedure.reasons.get(0);
            procedureRecord.put("reason_code", reason.code);
            procedureRecord.put("reason_description", reason.display);
        }

        return procedureRecord;
    }

    /**
     * Build a single Quality of Life record in avro
     * @param personId The person ID related to the quality of life record
     * @param year The year for the quality of life record
     * @param qol The QOL value
     * @param qaly The QALY value
     * @param daly The DALY value
     * @return The build quality of life record
     */
    private GenericData.Record buildQualityOfLifeRecord(String personId, Integer year, Double qol, Double qaly, Double daly) {
        GenericData.Record qualityOfLifeRecord = new GenericData.Record(schemas.get(ExportEvents.measure));

        qualityOfLifeRecord.put("subject", personId);
        qualityOfLifeRecord.put("year", String.valueOf(year));
        qualityOfLifeRecord.put("qol", String.valueOf(qol));
        qualityOfLifeRecord.put("qaly", String.valueOf(qaly));
        qualityOfLifeRecord.put("daly", String.valueOf(daly));

        return qualityOfLifeRecord;
    }

    /**
     * Build a single attribute record in avro
     * @param personId The person ID the state are related to
     * @param name The name of the attribute
     * @param value The value of the attribute
     * @return The built attribute record
     */
    private GenericData.Record buildAttributeRecord(String personId, String name, String value) {
        GenericData.Record attributeRecord = new GenericData.Record(schemas.get(ExportEvents.state));

        attributeRecord.put("subject", personId);
        attributeRecord.put("name", name);
        attributeRecord.put("value", value);

        return attributeRecord;
    }

    /**
     * Build observation list recursively. Observations have sub-observations with multiple levels of nesting.
     * This function recursively builds the observation list to be written by traversing through each sub observation.
     * @param recordList List of observation records
     * @param personId Person ID
     * @param encounterId Encounter ID
     * @param observation Observation
     */
    private void buildObservationList(List<GenericData.Record> recordList, String personId,
                                      String encounterId, Observation observation) {
        if (observation.value == null) {
            if (observation.observations != null && !observation.observations.isEmpty()) {
                // just loop through the child observation
                for (Observation subObs : observation.observations) {
                    buildObservationList(recordList, personId, encounterId, subObs);
                }
            }
        }
        recordList.add(buildObservationRecord(personId, encounterId, observation));
    }

}