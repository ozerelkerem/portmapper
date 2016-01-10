/*
 * Copyright (c) 2013-2016, Kasra Faghihi, All rights reserved.
 * 
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 3.0 of the License, or (at your option) any later version.
 * 
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 * 
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library.
 */
package com.offbynull.portmapper.io.process;

import com.offbynull.portmapper.BasicBus;
import com.offbynull.portmapper.Bus;
import com.offbynull.portmapper.io.network.internalmessages.IdentifiableErrorNetworkResponse;
import com.offbynull.portmapper.io.network.internalmessages.KillNetworkRequest;
import com.offbynull.portmapper.io.process.internalmessages.CreateProcessRequest;
import com.offbynull.portmapper.io.process.internalmessages.CreateProcessResponse;
import com.offbynull.portmapper.io.process.internalmessages.CloseProcessRequest;
import com.offbynull.portmapper.io.process.internalmessages.ErrorProcessResponse;
import com.offbynull.portmapper.io.process.internalmessages.ExitProcessNotification;
import com.offbynull.portmapper.io.process.internalmessages.ReadType;
import com.offbynull.portmapper.io.process.internalmessages.WriteEmptyProcessNotification;
import com.offbynull.portmapper.io.process.internalmessages.WriteProcessRequest;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.LinkedBlockingQueue;
import org.apache.commons.collections4.list.UnmodifiableList;

final class ProcessRunnable implements Runnable {
    
    private final Bus bus;
    private final LinkedBlockingQueue<Object> queue;
    private int nextId = 0;

    public ProcessRunnable() {
        queue = new LinkedBlockingQueue<>();
        bus = new BasicBus(queue);
    }
    private Map<Integer, ProcessEntry> idMap = new HashMap<>();

    public Bus getBus() {
        return bus;
    }

    @Override
    public void run() {
        try {
            while (true) {
                Object msg = queue.take();
                processMessage(msg);
            }
        } catch (KillRequestException kre) {
            // do nothing
        } catch (Exception e) {
            throw new RuntimeException(e); // rethrow exception
        } finally {
            shutdownResources();
        }
    }

    private void processMessage(Object msg) throws IOException {
        if (msg instanceof CreateProcessRequest) {
            CreateProcessRequest req = (CreateProcessRequest) msg;
            Bus responseBus = req.getResponseBus();
            Process process = null;
            try {
                String executable = req.getExecutable();
                UnmodifiableList<String> parameters = req.getParameters();
                List<String> command = new LinkedList<>();
                command.add(executable);
                command.addAll(parameters);
                ProcessBuilder pb = new ProcessBuilder(command);
                process = pb.start();
                
                int id = nextId++;
                
                ProcessExitRunnable exitRunnable = new ProcessExitRunnable(id, process, responseBus);
                Thread exitThread = new Thread(exitRunnable);
                ProcessReaderRunnable stdoutRunnable = new ProcessReaderRunnable(id, process.getInputStream(), responseBus,
                        ReadType.STDOUT);
                Thread stdoutThread = new Thread(stdoutRunnable);
                ProcessReaderRunnable stderrRunnable = new ProcessReaderRunnable(id, process.getErrorStream(), responseBus,
                        ReadType.STDERR);
                Thread stderrThread = new Thread(stderrRunnable);
                ProcessWriterRunnable stdinRunnable = new ProcessWriterRunnable(id, process.getOutputStream(), responseBus);
                Thread stdinThread = new Thread(stdinRunnable);
                
                stdoutThread.start();
                stderrThread.start();
                stdinThread.start();
                exitThread.start();
                
                ProcessEntry entry = new ProcessEntry(process, exitThread, stdinThread, stdoutThread, stderrThread,
                        stdinRunnable.getLocalInputBus(), id, responseBus);
                idMap.put(id, entry);
                responseBus.send(new CreateProcessResponse(id));
            } catch (RuntimeException re) {
                if (process != null) {
                    process.destroy();
                }
                responseBus.send(new ErrorProcessResponse());
            }
        } else if (msg instanceof CloseProcessRequest) {
            CloseProcessRequest req = (CloseProcessRequest) msg;
            int id = req.getId();
            ProcessEntry entry = idMap.get(id);
            if (entry != null) {
                entry.getProcess().destroy();
                // what happens next is that the thread responsible for checking the process state will find out that it died, then send a
                // "TerminatedMessage" back to this gateway to initiate cleanup
            }
        } else if (msg instanceof TerminatedMessage) {
            // sent internally once process exits -- not by user
            TerminatedMessage req = (TerminatedMessage) msg;
            int id = req.getId();
            try {
                ProcessEntry entry = idMap.remove(id);
                if (entry != null) {
                    Bus responseBus = entry.getResponseBus();
                    entry.getProcess().destroy();
                    entry.getStdoutThread().interrupt();
                    entry.getStderrThread().interrupt();
                    entry.getStdinThread().interrupt();
                    entry.getExitThread().interrupt();
                    
                    Integer exitCode = req.getExitCode();
                    
                    if (exitCode == null) {
                        responseBus.send(new IdentifiableErrorNetworkResponse(id));
                    } else {
                        responseBus.send(new ExitProcessNotification(exitCode, id));
                    }
                }
            } catch (RuntimeException re) {
                // do nothing, process should alreayd be dead at this point
            }
        } else if (msg instanceof WriteEmptyMessage) {
            // sent internally once process has nothing else to write out
            WriteEmptyMessage req = (WriteEmptyMessage) msg;
            int id = req.getId();
            ProcessEntry entry = idMap.remove(id);
            if (entry != null) {
                Bus responseBus = entry.getResponseBus();
                responseBus.send(new WriteEmptyProcessNotification(id));
            }
        } else if (msg instanceof ReadMessage) {
            // sent internally once process has read something in
            WriteEmptyMessage req = (WriteEmptyMessage) msg;
            int id = req.getId();
            ProcessEntry entry = idMap.remove(id);
            if (entry != null) {
                Bus responseBus = entry.getResponseBus();
                responseBus.send(new WriteEmptyProcessNotification(id));
            }
        } else if (msg instanceof WriteProcessRequest) {
            WriteProcessRequest req = (WriteProcessRequest) msg;
            Bus responseBus = null;
            Process process = null;
            int id = req.getId();
            try {
                ProcessEntry entry = idMap.get(id);
                responseBus = entry.getResponseBus();
                process = entry.getProcess();
                entry.getStdinBus().send(ByteBuffer.wrap(req.getData()));
            } catch (RuntimeException re) {
                if (process != null) {
                    process.destroy();
                } else if (responseBus != null) {
                    responseBus.send(new IdentifiableErrorNetworkResponse(id));
                }
            }
        } else if (msg instanceof KillNetworkRequest) {
            throw new KillRequestException();
        }
    }

    private void shutdownResources() {
        for (Entry<Integer, ProcessEntry> entry : idMap.entrySet()) {
            ProcessEntry processEntry = entry.getValue();
            try {
                processEntry.getProcess().destroy();
                processEntry.getStdoutThread().interrupt();
                processEntry.getStderrThread().interrupt();
                processEntry.getStdinThread().interrupt();
                processEntry.getExitThread().join();
            } catch (InterruptedException ie) {
                throw new RuntimeException(ie);
            } catch (RuntimeException e) {
                // do nothing
            }
        }
        idMap.clear();
    }
    
    
    private static final class KillRequestException extends RuntimeException {
        private static final long serialVersionUID = 1L;

    }
}