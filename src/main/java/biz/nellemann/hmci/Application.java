/*
   Copyright 2020 mark.nellemann@gmail.com

   Licensed under the Apache License, Version 2.0 (the "License");
   you may not use this file except in compliance with the License.
   You may obtain a copy of the License at

       http://www.apache.org/licenses/LICENSE-2.0

   Unless required by applicable law or agreed to in writing, software
   distributed under the License is distributed on an "AS IS" BASIS,
   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
   See the License for the specific language governing permissions and
   limitations under the License.
 */
package biz.nellemann.hmci;

import picocli.CommandLine;
import picocli.CommandLine.Option;
import picocli.CommandLine.Command;
import org.slf4j.impl.SimpleLogger;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;

@Command(name = "hmci",
    mixinStandardHelpOptions = true,
    description = "HMC Insights.",
    versionProvider = biz.nellemann.hmci.VersionProvider.class)
public class Application implements Callable<Integer> {

    @Option(names = { "-c", "--conf" }, description = "Configuration file [default: '/etc/hmci.toml'].", defaultValue = "/etc/hmci.toml", paramLabel = "<file>")
    private String configurationFile;

    @Option(names = { "-d", "--debug" }, description = "Enable debugging [default: 'false'].")
    private boolean[] enableDebug = new boolean[0];

    public static void main(String... args) {
        int exitCode = new CommandLine(new Application()).execute(args);
        System.exit(exitCode);
    }


    @Override
    public Integer call() throws IOException {

        Configuration configuration;
        InfluxClient influxClient;
        List<Thread> threadList = new ArrayList<>();

        File file = new File(configurationFile);
        if(!file.exists()) {
            System.err.println("Error - No configuration file found at: " + file.toString());
            return -1;
        }

        switch (enableDebug.length) {
            case 1:
                System.out.println("DEBUG");
                System.setProperty(SimpleLogger.DEFAULT_LOG_LEVEL_KEY, "DEBUG");
                break;
            case 2:
                System.out.println("TRACE");
                System.setProperty(SimpleLogger.DEFAULT_LOG_LEVEL_KEY, "TRACE");
                break;
        }

        try {
            configuration = new Configuration(configurationFile);
            influxClient = new InfluxClient(configuration.getInflux());
            influxClient.login();

            for(Configuration.HmcObject configHmc : configuration.getHmc()) {
                Thread t = new Thread(new HmcInstance(configHmc, influxClient));
                t.setName(configHmc.name);
                t.start();
                threadList.add(t);
            }

            for (Thread thread : threadList) {
                thread.join();
            }

        } catch (InterruptedException | RuntimeException e) {
            System.err.println(e.getMessage());
            return 1;
        }

        return 0;
    }

}
