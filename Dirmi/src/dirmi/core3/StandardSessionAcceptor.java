/*
 *  Copyright 2008 Brian S O'Neill
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package dirmi.core3;

import java.io.IOException;

import java.util.concurrent.ScheduledExecutorService;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import dirmi.Session;
import dirmi.SessionAcceptor;
import dirmi.SessionListener;

import dirmi.io2.StreamBroker;
import dirmi.io2.StreamBrokerAcceptor;
import dirmi.io2.StreamBrokerListener;

/**
 * 
 *
 * @author Brian S O'Neill
 */
public class StandardSessionAcceptor implements SessionAcceptor {
    private final StreamBrokerAcceptor mAcceptor;
    private final ScheduledExecutorService mExecutor;
    private final Log mLog;

    public StandardSessionAcceptor(StreamBrokerAcceptor acceptor,
                                   ScheduledExecutorService executor)
    {
        this(acceptor, executor, null);
    }

    /**
     * @param executor shared executor for remote methods
     * @param log message log; pass null for default
     */
    public StandardSessionAcceptor(StreamBrokerAcceptor acceptor,
                                   ScheduledExecutorService executor, Log log)
    {
        if (acceptor == null) {
            throw new IllegalArgumentException("Broker acceptor is null");
        }
        if (executor == null) {
            throw new IllegalArgumentException("Executor is null");
        }
        if (log == null) {
            log = LogFactory.getLog(SessionAcceptor.class);
        }

        mAcceptor = acceptor;
        mExecutor = executor;
        mLog = log;
    }

    public void accept(final Object server, final SessionListener listener) {
        mAcceptor.accept(new StreamBrokerListener() {
            public void established(StreamBroker broker) {
                Session session;
                try {
                    session = new StandardSession(broker, server, mExecutor, mLog);
                } catch (IOException e) {
                    try {
                        broker.close();
                    } catch (IOException e2) {
                        // Ignore.
                    }
                    failed(e);
                    return;
                }

                listener.established(session);
            }

            public void failed(IOException e) {
                listener.failed(e);
            }
        });
    }

    public void close() throws IOException {
        mAcceptor.close();
    }
}