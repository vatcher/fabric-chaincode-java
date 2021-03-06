/*
Copyright IBM Corp. All Rights Reserved.

SPDX-License-Identifier: Apache-2.0
*/
package org.hyperledger.fabric.shim.fvt;

import com.google.protobuf.ByteString;
import org.hyperledger.fabric.protos.peer.Chaincode;
import org.hyperledger.fabric.protos.peer.ChaincodeShim;
import org.hyperledger.fabric.protos.peer.ProposalResponsePackage;
import org.hyperledger.fabric.shim.ChaincodeBase;
import org.hyperledger.fabric.shim.ChaincodeStub;
import org.hyperledger.fabric.shim.chaincode.EmptyChaincode;
import org.hyperledger.fabric.shim.ledger.KeyModification;
import org.hyperledger.fabric.shim.ledger.KeyValue;
import org.hyperledger.fabric.shim.ledger.QueryResultsIterator;
import org.hyperledger.fabric.shim.mock.peer.*;
import org.hyperledger.fabric.shim.utils.MessageUtil;
import org.hyperledger.fabric.shim.utils.TimeoutUtil;
import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.contrib.java.lang.system.EnvironmentVariables;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.logging.Level;
import java.util.logging.Logger;

import static org.hamcrest.Matchers.is;
import static org.hyperledger.fabric.protos.peer.ChaincodeShim.ChaincodeMessage.Type.*;
import static org.junit.Assert.*;

public class ChaincodeFVTest {

    @Rule
    public final EnvironmentVariables environmentVariables = new EnvironmentVariables();

    ChaincodeMockPeer server;

    @After
    public void afterTest() throws Exception {
        if (server != null) {
            server.stop();
            server = null;
        }
    }

    @Test
    public void testRegister() throws Exception {
        ChaincodeBase cb = new EmptyChaincode();

        List<ScenarioStep> scenario = new ArrayList<>();
        scenario.add(new RegisterStep());

        server = ChaincodeMockPeer.startServer(scenario);

        cb.start(new String[]{"-a", "127.0.0.1:7052", "-i", "testId"});

        checkScenarioStepEnded(server, 1, 5000, TimeUnit.MILLISECONDS);

        assertThat(server.getLastMessageSend().getType(), is(READY));
        assertThat(server.getLastMessageRcvd().getType(), is(REGISTER));
    }

    @Test
    public void testRegisterAndEmptyInit() throws Exception {
        ChaincodeBase cb = new ChaincodeBase() {
            @Override
            public Response init(ChaincodeStub stub) {
                return newSuccessResponse();
            }

            @Override
            public Response invoke(ChaincodeStub stub) {
                return newSuccessResponse();
            }
        };

        ByteString payload = org.hyperledger.fabric.protos.peer.Chaincode.ChaincodeInput.newBuilder().addArgs(ByteString.copyFromUtf8("")).build().toByteString();
        ChaincodeShim.ChaincodeMessage initMsg = MessageUtil.newEventMessage(INIT, "testChannel", "0", payload, null);

        List<ScenarioStep> scenario = new ArrayList<>();
        scenario.add(new RegisterStep());
        scenario.add(new CompleteStep());

        server = ChaincodeMockPeer.startServer(scenario);

        cb.start(new String[]{"-a", "127.0.0.1:7052", "-i", "testId"});
        checkScenarioStepEnded(server, 1, 5000, TimeUnit.MILLISECONDS);

        server.send(initMsg);
        checkScenarioStepEnded(server, 2, 5000, TimeUnit.MILLISECONDS);

        assertThat(server.getLastMessageSend().getType(), is(INIT));
        assertThat(server.getLastMessageRcvd().getType(), is(COMPLETED));
    }

    @Test
    public void testInitAndInvoke() throws Exception {
        ChaincodeBase cb = new ChaincodeBase() {
            @Override
            public Response init(ChaincodeStub stub) {
                assertThat(stub.getFunction(), is("init"));
                assertThat(stub.getArgs().size(), is(3));
                stub.putState("a", ByteString.copyFromUtf8("100").toByteArray());
                return newSuccessResponse("OK response1");
            }

            @Override
            public Response invoke(ChaincodeStub stub) {
                assertThat(stub.getFunction(), is("invoke"));
                assertThat(stub.getArgs().size(), is(3));
                String aKey = stub.getStringArgs().get(1);
                assertThat(aKey, is("a"));
                String aVal = stub.getStringState(aKey);
                stub.putState(aKey, ByteString.copyFromUtf8("120").toByteArray());
                stub.delState("delKey");
                return newSuccessResponse("OK response2");
            }
        };

        ByteString initPayload = Chaincode.ChaincodeInput.newBuilder()
                .addArgs(ByteString.copyFromUtf8("init"))
                .addArgs(ByteString.copyFromUtf8("a"))
                .addArgs(ByteString.copyFromUtf8("100"))
                .build().toByteString();
        ChaincodeShim.ChaincodeMessage initMsg = MessageUtil.newEventMessage(INIT, "testChannel", "0", initPayload, null);

        List<ScenarioStep> scenario = new ArrayList<>();
        scenario.add(new RegisterStep());
        scenario.add(new PutValueStep("100"));
        scenario.add(new CompleteStep());
        scenario.add(new GetValueStep("100"));
        scenario.add(new PutValueStep("120"));
        scenario.add(new DelValueStep());
        scenario.add(new CompleteStep());

        setLogLevel("DEBUG");
        server = ChaincodeMockPeer.startServer(scenario);

        cb.start(new String[]{"-a", "127.0.0.1:7052", "-i", "testId"});
        checkScenarioStepEnded(server, 1, 5000, TimeUnit.MILLISECONDS);

        server.send(initMsg);
        checkScenarioStepEnded(server, 3, 5000, TimeUnit.MILLISECONDS);

        assertThat(server.getLastMessageSend().getType(), is(RESPONSE));
        assertThat(server.getLastMessageRcvd().getType(), is(COMPLETED));
        assertThat(ProposalResponsePackage.Response.parseFrom(server.getLastMessageRcvd().getPayload()).getMessage(), is("OK response1"));


        ByteString invokePayload = Chaincode.ChaincodeInput.newBuilder()
                .addArgs(ByteString.copyFromUtf8("invoke"))
                .addArgs(ByteString.copyFromUtf8("a"))
                .addArgs(ByteString.copyFromUtf8("10"))
                .build().toByteString();
        ChaincodeShim.ChaincodeMessage invokeMsg = MessageUtil.newEventMessage(TRANSACTION, "testChannel", "0", invokePayload, null);

        server.send(invokeMsg);

        checkScenarioStepEnded(server, 7, 5000, TimeUnit.MILLISECONDS);
        assertThat(server.getLastMessageSend().getType(), is(RESPONSE));
        assertThat(server.getLastMessageRcvd().getType(), is(COMPLETED));
        assertThat(ProposalResponsePackage.Response.parseFrom(server.getLastMessageRcvd().getPayload()).getMessage(), is("OK response2"));
    }

    @Test
    public void testInvokeRangeQ() throws Exception {
        ChaincodeBase cb = new ChaincodeBase() {
            @Override
            public Response init(ChaincodeStub stub) {
                return newSuccessResponse("OK response1");
            }

            @Override
            public Response invoke(ChaincodeStub stub) {
                assertThat(stub.getFunction(), is("invoke"));
                assertThat(stub.getArgs().size(), is(3));
                String aKey = stub.getStringArgs().get(1);
                String bKey = stub.getStringArgs().get(2);

                QueryResultsIterator<KeyValue> stateByRange = stub.getStateByRange(aKey, bKey);
                Iterator<KeyValue> iter = stateByRange.iterator();
                while (iter.hasNext()) {
                    iter.next();
                }
                try {
                    stateByRange.close();
                } catch (Exception e) {
                    fail("No exception expected");
                }
                return newSuccessResponse("OK response2");
            }
        };

        ByteString initPayload = Chaincode.ChaincodeInput.newBuilder()
                .addArgs(ByteString.copyFromUtf8(""))
                .build().toByteString();
        ChaincodeShim.ChaincodeMessage initMsg = MessageUtil.newEventMessage(INIT, "testChannel", "0", initPayload, null);

        ByteString invokePayload = Chaincode.ChaincodeInput.newBuilder()
                .addArgs(ByteString.copyFromUtf8("invoke"))
                .addArgs(ByteString.copyFromUtf8("a"))
                .addArgs(ByteString.copyFromUtf8("b"))
                .build().toByteString();
        ChaincodeShim.ChaincodeMessage invokeMsg = MessageUtil.newEventMessage(TRANSACTION, "testChannel", "0", invokePayload, null);

        List<ScenarioStep> scenario = new ArrayList<>();
        scenario.add(new RegisterStep());
        scenario.add(new CompleteStep());
        scenario.add(new GetStateByRangeStep(false, "a", "b"));
        scenario.add(new QueryCloseStep());
        scenario.add(new CompleteStep());
        scenario.add(new GetStateByRangeStep(true, "a", "b"));
        scenario.add(new QueryNextStep(false, "c"));
        scenario.add(new QueryCloseStep());
        scenario.add(new CompleteStep());

        setLogLevel("DEBUG");
        server = ChaincodeMockPeer.startServer(scenario);

        cb.start(new String[]{"-a", "127.0.0.1:7052", "-i", "testId"});
        checkScenarioStepEnded(server, 1, 5000, TimeUnit.MILLISECONDS);

        server.send(initMsg);
        checkScenarioStepEnded(server, 2, 5000, TimeUnit.MILLISECONDS);


        server.send(invokeMsg);

        checkScenarioStepEnded(server, 5, 5000, TimeUnit.MILLISECONDS);
        assertThat(server.getLastMessageSend().getType(), is(RESPONSE));
        assertThat(server.getLastMessageRcvd().getType(), is(COMPLETED));
        assertThat(ProposalResponsePackage.Response.parseFrom(server.getLastMessageRcvd().getPayload()).getMessage(), is("OK response2"));

        server.send(invokeMsg);

        checkScenarioStepEnded(server, 9, 5000, TimeUnit.MILLISECONDS);
        assertThat(server.getLastMessageSend().getType(), is(RESPONSE));
        assertThat(server.getLastMessageRcvd().getType(), is(COMPLETED));
        assertThat(ProposalResponsePackage.Response.parseFrom(server.getLastMessageRcvd().getPayload()).getMessage(), is("OK response2"));
    }

    @Test
    public void testGetQueryResult() throws Exception {
        ChaincodeBase cb = new ChaincodeBase() {
            @Override
            public Response init(ChaincodeStub stub) {
                return newSuccessResponse("OK response1");
            }

            @Override
            public Response invoke(ChaincodeStub stub) {
                String query = stub.getStringArgs().get(1);

                QueryResultsIterator<KeyValue> queryResult = stub.getQueryResult(query);
                Iterator<KeyValue> iter = queryResult.iterator();
                while (iter.hasNext()) {
                    iter.next();
                }
                try {
                    queryResult.close();
                } catch (Exception e) {
                    fail("No exception expected");
                }
                return newSuccessResponse("OK response2");
            }
        };

        ByteString initPayload = Chaincode.ChaincodeInput.newBuilder()
                .addArgs(ByteString.copyFromUtf8(""))
                .build().toByteString();
        ChaincodeShim.ChaincodeMessage initMsg = MessageUtil.newEventMessage(INIT, "testChannel", "0", initPayload, null);

        ByteString invokePayload = Chaincode.ChaincodeInput.newBuilder()
                .addArgs(ByteString.copyFromUtf8("invoke"))
                .addArgs(ByteString.copyFromUtf8("query"))
                .build().toByteString();
        ChaincodeShim.ChaincodeMessage invokeMsg = MessageUtil.newEventMessage(TRANSACTION, "testChannel", "0", invokePayload, null);

        List<ScenarioStep> scenario = new ArrayList<>();
        scenario.add(new RegisterStep());
        scenario.add(new CompleteStep());
        scenario.add(new GetQueryResultStep(false, "a", "b"));
        scenario.add(new QueryCloseStep());
        scenario.add(new CompleteStep());
        scenario.add(new GetQueryResultStep(true, "a", "b"));
        scenario.add(new QueryNextStep(false, "c"));
        scenario.add(new QueryCloseStep());
        scenario.add(new CompleteStep());

        setLogLevel("DEBUG");
        server = ChaincodeMockPeer.startServer(scenario);

        cb.start(new String[]{"-a", "127.0.0.1:7052", "-i", "testId"});
        checkScenarioStepEnded(server, 1, 5000, TimeUnit.MILLISECONDS);

        server.send(initMsg);
        checkScenarioStepEnded(server, 2, 5000, TimeUnit.MILLISECONDS);

        server.send(invokeMsg);

        checkScenarioStepEnded(server, 5, 5000, TimeUnit.MILLISECONDS);
        assertThat(server.getLastMessageSend().getType(), is(RESPONSE));
        assertThat(server.getLastMessageRcvd().getType(), is(COMPLETED));
        assertThat(ProposalResponsePackage.Response.parseFrom(server.getLastMessageRcvd().getPayload()).getMessage(), is("OK response2"));

        server.send(invokeMsg);

        checkScenarioStepEnded(server, 9, 5000, TimeUnit.MILLISECONDS);
        assertThat(server.getLastMessageSend().getType(), is(RESPONSE));
        assertThat(server.getLastMessageRcvd().getType(), is(COMPLETED));
        assertThat(ProposalResponsePackage.Response.parseFrom(server.getLastMessageRcvd().getPayload()).getMessage(), is("OK response2"));
    }

    @Test
    public void testGetHistoryForKey() throws Exception {
        ChaincodeBase cb = new ChaincodeBase() {
            @Override
            public Response init(ChaincodeStub stub) {
                return newSuccessResponse("OK response1");
            }

            @Override
            public Response invoke(ChaincodeStub stub) {
                String key = stub.getStringArgs().get(1);

                QueryResultsIterator<KeyModification> queryResult = stub.getHistoryForKey(key);
                Iterator<KeyModification> iter = queryResult.iterator();
                while (iter.hasNext()) {
                    iter.next();
                }
                try {
                    queryResult.close();
                } catch (Exception e) {
                    fail("No exception expected");
                }
                return newSuccessResponse("OK response2");
            }
        };

        ByteString initPayload = Chaincode.ChaincodeInput.newBuilder()
                .addArgs(ByteString.copyFromUtf8(""))
                .build().toByteString();
        ChaincodeShim.ChaincodeMessage initMsg = MessageUtil.newEventMessage(INIT, "testChannel", "0", initPayload, null);

        ByteString invokePayload = Chaincode.ChaincodeInput.newBuilder()
                .addArgs(ByteString.copyFromUtf8("invoke"))
                .addArgs(ByteString.copyFromUtf8("key1"))
                .build().toByteString();
        ChaincodeShim.ChaincodeMessage invokeMsg = MessageUtil.newEventMessage(TRANSACTION, "testChannel", "0", invokePayload, null);

        List<ScenarioStep> scenario = new ArrayList<>();
        scenario.add(new RegisterStep());
        scenario.add(new CompleteStep());
        scenario.add(new GetHistoryForKeyStep(false, "1", "2"));
        scenario.add(new QueryCloseStep());
        scenario.add(new CompleteStep());

        setLogLevel("DEBUG");
        server = ChaincodeMockPeer.startServer(scenario);

        cb.start(new String[]{"-a", "127.0.0.1:7052", "-i", "testId"});
        checkScenarioStepEnded(server, 1, 5000, TimeUnit.MILLISECONDS);

        server.send(initMsg);
        checkScenarioStepEnded(server, 2, 5000, TimeUnit.MILLISECONDS);

        server.send(invokeMsg);

        checkScenarioStepEnded(server, 5, 5000, TimeUnit.MILLISECONDS);
        assertThat(server.getLastMessageSend().getType(), is(RESPONSE));
        assertThat(server.getLastMessageRcvd().getType(), is(COMPLETED));
        assertThat(ProposalResponsePackage.Response.parseFrom(server.getLastMessageRcvd().getPayload()).getMessage(), is("OK response2"));

    }

    @Test
    public void testInvokeChaincode() throws Exception {
        ChaincodeBase cb = new ChaincodeBase() {
            @Override
            public Response init(ChaincodeStub stub) {
                return newSuccessResponse("OK response1");
            }

            @Override
            public Response invoke(ChaincodeStub stub) {
                Response response = stub.invokeChaincode("anotherChaincode", Collections.EMPTY_LIST);
                return newSuccessResponse("OK response2");
            }
        };

        ByteString initPayload = Chaincode.ChaincodeInput.newBuilder()
                .addArgs(ByteString.copyFromUtf8(""))
                .build().toByteString();
        ChaincodeShim.ChaincodeMessage initMsg = MessageUtil.newEventMessage(INIT, "testChannel", "0", initPayload, null);

        ByteString invokePayload = Chaincode.ChaincodeInput.newBuilder()
                .addArgs(ByteString.copyFromUtf8("invoke"))
                .build().toByteString();
        ChaincodeShim.ChaincodeMessage invokeMsg = MessageUtil.newEventMessage(TRANSACTION, "testChannel", "0", invokePayload, null);

        List<ScenarioStep> scenario = new ArrayList<>();
        scenario.add(new RegisterStep());
        scenario.add(new CompleteStep());
        scenario.add(new InvokeChaincodeStep());
        scenario.add(new CompleteStep());

        setLogLevel("DEBUG");
        server = ChaincodeMockPeer.startServer(scenario);

        cb.start(new String[]{"-a", "127.0.0.1:7052", "-i", "testId"});
        checkScenarioStepEnded(server, 1, 5000, TimeUnit.MILLISECONDS);

        server.send(initMsg);
        checkScenarioStepEnded(server, 2, 5000, TimeUnit.MILLISECONDS);

        server.send(invokeMsg);

        checkScenarioStepEnded(server, 4, 5000, TimeUnit.MILLISECONDS);
        assertThat(server.getLastMessageSend().getType(), is(RESPONSE));
        assertThat(server.getLastMessageRcvd().getType(), is(COMPLETED));
    }

    @Test
    public void testErrorInitInvoke() throws Exception {
        ChaincodeBase cb = new ChaincodeBase() {
            @Override
            public Response init(ChaincodeStub stub) {
                return newErrorResponse("Wrong response1");
            }

            @Override
            public Response invoke(ChaincodeStub stub) {
                return newErrorResponse("Wrong response2");
            }
        };

        ByteString payload = org.hyperledger.fabric.protos.peer.Chaincode.ChaincodeInput.newBuilder().addArgs(ByteString.copyFromUtf8("")).build().toByteString();
        ChaincodeShim.ChaincodeMessage initMsg = MessageUtil.newEventMessage(INIT, "testChannel", "0", payload, null);

        List<ScenarioStep> scenario = new ArrayList<>();
        scenario.add(new RegisterStep());
        scenario.add(new ErrorResponseStep());
        scenario.add(new ErrorResponseStep());

        setLogLevel("DEBUG");
        server = ChaincodeMockPeer.startServer(scenario);

        cb.start(new String[]{"-a", "127.0.0.1:7052", "-i", "testId"});
        checkScenarioStepEnded(server, 1, 5000, TimeUnit.MILLISECONDS);

        server.send(initMsg);
        checkScenarioStepEnded(server, 2, 5000, TimeUnit.MILLISECONDS);

        assertThat(server.getLastMessageSend().getType(), is(INIT));
        assertThat(server.getLastMessageRcvd().getType(), is(ERROR));
        assertThat(server.getLastMessageRcvd().getPayload().toStringUtf8(), is("Wrong response1"));

        ByteString invokePayload = Chaincode.ChaincodeInput.newBuilder()
                .build().toByteString();
        ChaincodeShim.ChaincodeMessage invokeMsg = MessageUtil.newEventMessage(TRANSACTION, "testChannel", "0", invokePayload, null);

        server.send(invokeMsg);

        checkScenarioStepEnded(server, 3, 5000, TimeUnit.MILLISECONDS);
        assertThat(server.getLastMessageSend().getType(), is(TRANSACTION));
        assertThat(server.getLastMessageRcvd().getType(), is(ERROR));
        assertThat(server.getLastMessageRcvd().getPayload().toStringUtf8(), is("Wrong response2"));
    }

    @Test
    public void testStreamShutdown() throws Exception {
        ChaincodeBase cb = new ChaincodeBase() {
            @Override
            public Response init(ChaincodeStub stub) {
                try {
                    Thread.sleep(10);
                } catch (InterruptedException e) {
                }
                return newSuccessResponse();
            }

            @Override
            public Response invoke(ChaincodeStub stub) {
                return newSuccessResponse();
            }
        };

        ByteString payload = org.hyperledger.fabric.protos.peer.Chaincode.ChaincodeInput.newBuilder().addArgs(ByteString.copyFromUtf8("")).build().toByteString();
        ChaincodeShim.ChaincodeMessage initMsg = MessageUtil.newEventMessage(INIT, "testChannel", "0", payload, null);

        List<ScenarioStep> scenario = new ArrayList<>();
        scenario.add(new RegisterStep());
        scenario.add(new CompleteStep());

        setLogLevel("DEBUG");
        server = ChaincodeMockPeer.startServer(scenario);

        cb.start(new String[]{"-a", "127.0.0.1:7052", "-i", "testId"});
        checkScenarioStepEnded(server, 1, 5000, TimeUnit.MILLISECONDS);
        server.send(initMsg);
        server.stop();
        server = null;
    }

    @Test
    public void testChaincodeLogLevel() throws Exception {
        ChaincodeBase cb = new EmptyChaincode();

        List<ScenarioStep> scenario = new ArrayList<>();
        scenario.add(new RegisterStep());
        scenario.add(new CompleteStep());

        setLogLevel("DEBUG");
        server = ChaincodeMockPeer.startServer(scenario);

        cb.start(new String[]{"-a", "127.0.0.1:7052", "-i", "testId"});

        assertEquals("Wrong debug level for " + cb.getClass().getPackage().getName(), Level.FINEST, Logger.getLogger(cb.getClass().getPackage().getName()).getLevel());

    }

    public static void checkScenarioStepEnded(final ChaincodeMockPeer s, final int step, final int timeout, final TimeUnit units) throws Exception {
        try {
            TimeoutUtil.runWithTimeout(new Thread(() -> {
                while (true) {
                    if (s.getLastExecutedStep() == step) return;
                    try {
                        Thread.sleep(1);
                    } catch (InterruptedException e) {
                    }
                }
            }), timeout, units);
        } catch (TimeoutException e) {
            fail("Got timeout, first step not finished");
        }
    }

    public void setLogLevel(String logLevel) {
        environmentVariables.set("CORE_CHAINCODE_LOGGING_SHIM", logLevel);
        environmentVariables.set("CORE_CHAINCODE_LOGGING_LEVEL", logLevel);
    }
}
