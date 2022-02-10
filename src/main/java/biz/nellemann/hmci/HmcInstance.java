/*
 *    Copyright 2020 Mark Nellemann <mark.nellemann@gmail.com>
 *
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License.
 */
package biz.nellemann.hmci;

import biz.nellemann.hmci.Configuration.HmcObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static java.lang.Thread.sleep;

class HmcInstance implements Runnable {

    private final static Logger log = LoggerFactory.getLogger(HmcInstance.class);

    private final String hmcId;
    private final Long updateValue;
    private final Long rescanValue;
    private final Map<String,ManagedSystem> systems = new HashMap<>();
    private final Map<String, LogicalPartition> partitions = new HashMap<>();

    private final HmcRestClient hmcRestClient;
    private final InfluxClient influxClient;
    private final AtomicBoolean keepRunning = new AtomicBoolean(true);

    private File traceDir;
    private Boolean doTrace = false;
    private Boolean doEnergy = true;

    HmcInstance(HmcObject configHmc, InfluxClient influxClient) {
        this.hmcId = configHmc.name;
        this.updateValue = configHmc.update;
        this.rescanValue = configHmc.rescan;
        this.doEnergy = configHmc.energy;
        this.influxClient = influxClient;
        hmcRestClient = new HmcRestClient(configHmc.url, configHmc.username, configHmc.password, configHmc.unsafe);
        log.debug("HmcInstance() - id: {}, update: {}, refresh {}", hmcId, updateValue, rescanValue);

        if(configHmc.trace != null) {
            try {
                traceDir = new File(configHmc.trace);
                traceDir.mkdirs();
                if(traceDir.canWrite()) {
                    doTrace = true;
                } else {
                    log.warn("HmcInstance() - can't write to trace dir: " + traceDir.toString());
                }
            } catch (Exception e) {
                log.error("HmcInstance() - trace error: " + e.getMessage());
            }
        }
    }


    @Override
    public String toString() {
        return hmcId;
    }


    @Override
    public void run() {

        log.trace("run() - " + hmcId);
        int executions = 0;

        discover();

        do {
            Instant instantStart = Instant.now();
            try {
                if (doEnergy) {
                    getMetricsForEnergy();
                }
                getMetricsForSystems();
                getMetricsForPartitions();

                writeMetricsForSystemEnergy();
                writeMetricsForManagedSystems();
                writeMetricsForLogicalPartitions();
                influxClient.writeBatchPoints();

                // Refresh
                if (++executions > rescanValue) {
                    executions = 0;
                    discover();
                }
            } catch (Exception e) {
                log.error("run() - fatal error: {}", e.getMessage());
                throw new RuntimeException(e);
            }

            Instant instantEnd = Instant.now();
            long timeSpend = Duration.between(instantStart, instantEnd).toMillis();
            log.trace("run() - duration millis: " + timeSpend);
            if(timeSpend < (updateValue * 1000)) {
                try {
                    long sleepTime = (updateValue * 1000) - timeSpend;
                    log.trace("run() - sleeping millis: " + sleepTime);
                    if(sleepTime > 0) {
                        //noinspection BusyWait
                        sleep(sleepTime);
                    }
                } catch (InterruptedException e) {
                    log.error("run() - sleep interrupted", e);
                }
            } else {
                log.warn("run() - possible slow response from this HMC");
            }

        } while (keepRunning.get());

        // Logout of HMC
        try {
            hmcRestClient.logoff();
        } catch (IOException e) {
            log.warn("run() - error logging out of HMC: " + e.getMessage());
        }

    }


    void discover() {

        log.trace("discover()");

        Map<String, LogicalPartition> tmpPartitions = new HashMap<>();

        try {
            hmcRestClient.login();
            hmcRestClient.getManagedSystems().forEach((systemId, system) -> {

                // Add to list of known systems
                if(!systems.containsKey(systemId)) {
                    systems.put(systemId, system);
                    log.info("discover() - Found ManagedSystem: " + system);
                    if(doEnergy) {
                        hmcRestClient.enableEnergyMonitoring(system);
                    }
                }

                // Get partitions for this system
                try {
                    tmpPartitions.putAll(hmcRestClient.getLogicalPartitionsForManagedSystem(system));
                    if(!tmpPartitions.isEmpty()) {
                        partitions.clear();
                        partitions.putAll(tmpPartitions);
                    }
                } catch (Exception e) {
                    log.warn("discover() - getLogicalPartitions error: {}", e.getMessage());
                }

            });

        } catch(Exception e) {
            log.warn("discover() - getManagedSystems error: {}", e.getMessage());
        }


    }


    void getMetricsForSystems() {

        systems.forEach((systemId, system) -> {

            // Get and process metrics for this system
            String tmpJsonString = null;
            try {
                tmpJsonString = hmcRestClient.getPcmDataForManagedSystem(system);
            } catch (Exception e) {
                log.warn("getMetricsForSystems() - error: {}", e.getMessage());
            }

            if(tmpJsonString != null && !tmpJsonString.isEmpty()) {
                system.processMetrics(tmpJsonString);
                if(doTrace) {
                    writeTraceFile(systemId, tmpJsonString);
                }
            }


        });

    }


    void getMetricsForPartitions() {

        try {

            // Get partitions for this system
            partitions.forEach((partitionId, partition) -> {

                // Get and process metrics for this partition
                String tmpJsonString2 = null;
                try {
                    tmpJsonString2 = hmcRestClient.getPcmDataForLogicalPartition(partition);
                } catch (Exception e) {
                    log.warn("getMetricsForPartitions() - getPcmDataForLogicalPartition error: {}", e.getMessage());
                }
                if(tmpJsonString2 != null && !tmpJsonString2.isEmpty()) {
                    partition.processMetrics(tmpJsonString2);
                    if(doTrace) {
                        writeTraceFile(partitionId, tmpJsonString2);
                    }
                }

            });

        } catch(Exception e) {
            log.warn("getMetricsForPartitions() - error: {}", e.getMessage());
        }
    }


    void getMetricsForEnergy() {

        systems.forEach((systemId, system) -> {

            // Get and process metrics for this system
            String tmpJsonString = null;
            try {
                tmpJsonString = hmcRestClient.getPcmDataForEnergy(system.energy);
            } catch (Exception e) {
                log.warn("getMetricsForEnergy() - error: {}", e.getMessage());
            }

            if(tmpJsonString != null && !tmpJsonString.isEmpty()) {
                system.energy.processMetrics(tmpJsonString);
            }

        });

    }


    void writeMetricsForManagedSystems() {
        try {
            systems.forEach((systemId, system) -> influxClient.writeManagedSystem(system));
        } catch (NullPointerException npe) {
            log.warn("writeMetricsForManagedSystems() - NPE: {}", npe.getMessage(), npe);
        }
    }


    void writeMetricsForLogicalPartitions() {
        try {
            partitions.forEach((partitionId, partition) -> influxClient.writeLogicalPartition(partition));
        } catch (NullPointerException npe) {
            log.warn("writeMetricsForLogicalPartitions() - NPE: {}", npe.getMessage(), npe);
        }
    }


    void writeMetricsForSystemEnergy() {
        try {
            systems.forEach((systemId, system) -> influxClient.writeSystemEnergy(system.energy));
        } catch (NullPointerException npe) {
            log.warn("writeMetricsForSystemEnergy() - NPE: {}", npe.getMessage(), npe);
        }
    }


    private void writeTraceFile(String id, String json) {

        String fileName = String.format("%s-%s.json", id, Instant.now().toString());
        try {
            log.debug("Writing trace file: " + fileName);
            File traceFile = new File(traceDir, fileName);
            BufferedWriter writer = new BufferedWriter(new FileWriter(traceFile));
            writer.write(json);
            writer.close();
        } catch (IOException e) {
            log.warn("writeTraceFile() - " + e.getMessage());
        }
    }

}
