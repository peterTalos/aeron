/*
 * Copyright 2014-2023 Real Logic Limited.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.aeron.cluster;

import io.aeron.Aeron;
import io.aeron.CommonContext;
import io.aeron.Counter;
import io.aeron.CounterProvider;
import io.aeron.RethrowingErrorHandler;
import io.aeron.cluster.client.ClusterException;
import io.aeron.cluster.codecs.mark.MarkFileHeaderDecoder;
import io.aeron.cluster.service.ClusterMarkFile;
import io.aeron.driver.DefaultNameResolver;
import io.aeron.driver.NameResolver;
import io.aeron.exceptions.ConfigurationException;
import io.aeron.security.Authenticator;
import io.aeron.security.AuthenticatorSupplier;
import io.aeron.security.AuthorisationService;
import io.aeron.security.AuthorisationServiceSupplier;
import io.aeron.security.DefaultAuthenticatorSupplier;
import io.aeron.security.SessionProxy;
import io.aeron.test.TestContexts;
import io.aeron.test.Tests;
import io.aeron.test.cluster.TestClusterClock;
import org.agrona.DirectBuffer;
import org.agrona.SystemUtil;
import org.agrona.concurrent.AgentInvoker;
import org.agrona.concurrent.status.AtomicCounter;
import org.agrona.concurrent.status.CountersManager;
import org.agrona.concurrent.status.CountersReader;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;

import java.io.File;
import java.io.IOException;
import java.util.concurrent.TimeUnit;

import static io.aeron.AeronCounters.NODE_CONTROL_TOGGLE_TYPE_ID;
import static io.aeron.cluster.ConsensusModule.Configuration.*;
import static io.aeron.cluster.codecs.mark.ClusterComponentType.CONSENSUS_MODULE;
import static io.aeron.cluster.service.ClusterMarkFile.ERROR_BUFFER_MIN_LENGTH;
import static io.aeron.cluster.service.ClusteredServiceContainer.Configuration.MARK_FILE_DIR_PROP_NAME;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.*;

class ConsensusModuleContextTest
{
    @TempDir
    File clusterDir;

    private ConsensusModule.Context context;
    private final CountersManager countersManager = Tests.newCountersMananger(16 * 1024);
    private long registrationId = 0;

    @BeforeEach
    void beforeEach()
    {
        final Aeron.Context aeronContext = mock(Aeron.Context.class);
        when(aeronContext.subscriberErrorHandler()).thenReturn(new RethrowingErrorHandler());
        when(aeronContext.aeronDirectoryName()).thenReturn("some aeron dir");
        when(aeronContext.useConductorAgentInvoker()).thenReturn(true);
        final AgentInvoker conductorInvoker = mock(AgentInvoker.class);
        final Aeron aeron = mock(Aeron.class);
        when(aeron.addCounter(
            anyInt(), any(DirectBuffer.class), anyInt(), anyInt(), any(DirectBuffer.class), anyInt(), anyInt()))
            .thenAnswer(invocation -> mock(Counter.class));
        when(aeron.context()).thenReturn(aeronContext);
        when(aeron.conductorAgentInvoker()).thenReturn(conductorInvoker);
        when(aeron.countersReader()).thenReturn(countersManager);

        context = TestContexts.localhostConsensusModule()
            .clusterDir(clusterDir)
            .aeron(aeron)
            .errorCounter(mock(AtomicCounter.class))
            .ingressChannel("must be specified")
            .replicationChannel("must be specified")
            .moduleStateCounter(newCounter("moduleState", CONSENSUS_MODULE_STATE_TYPE_ID))
            .electionStateCounter(newCounter("electionState", ELECTION_STATE_TYPE_ID))
            .clusterNodeRoleCounter(newCounter("clusterNodeRole", CLUSTER_NODE_ROLE_TYPE_ID))
            .commitPositionCounter(newCounter("commitPosition", COMMIT_POSITION_TYPE_ID))
            .controlToggleCounter(newCounter("controlToggle", CONTROL_TOGGLE_TYPE_ID))
            .nodeControlToggleCounter(newCounter("nodeControlToggle", NODE_CONTROL_TOGGLE_TYPE_ID))
            .snapshotCounter(newCounter("snapshot", SNAPSHOT_COUNTER_TYPE_ID))
            .timedOutClientCounter(newCounter("timedOut", CLUSTER_CLIENT_TIMEOUT_COUNT_TYPE_ID));
    }

    private Counter newCounter(final String name, final int typeId)
    {
        final AtomicCounter atomicCounter = countersManager.newCounter(name, typeId);
        return new Counter(countersManager, ++registrationId, atomicCounter.id());
    }

    @AfterEach
    void afterEach()
    {
        context.close();
    }

    @ParameterizedTest
    @ValueSource(strings = { TIMER_SERVICE_SUPPLIER_WHEEL, TIMER_SERVICE_SUPPLIER_PRIORITY_HEAP })
    void validTimerServiceSupplier(final String supplierName)
    {
        System.setProperty(TIMER_SERVICE_SUPPLIER_PROP_NAME, supplierName);
        try
        {
            context.conclude();

            final TimerServiceSupplier supplier = context.timerServiceSupplier();
            assertNotNull(supplier);

            final TimerService.TimerHandler timerHandler = mock(TimerService.TimerHandler.class);
            final TimerService timerService = supplier.newInstance(context.clusterClock().timeUnit(), timerHandler);

            assertNotNull(timerService);
            assertEquals(supplierName, supplier.getClass().getName());
        }
        finally
        {
            System.clearProperty(TIMER_SERVICE_SUPPLIER_PROP_NAME);
        }
    }

    @Test
    void unknownTimerServiceSupplier()
    {
        final String supplierName = "unknown timer service supplier";
        System.setProperty(TIMER_SERVICE_SUPPLIER_PROP_NAME, supplierName);
        try
        {
            final ClusterException exception = assertThrows(ClusterException.class, context::conclude);
            assertEquals("ERROR - invalid TimerServiceSupplier: " + supplierName, exception.getMessage());
        }
        finally
        {
            System.clearProperty(TIMER_SERVICE_SUPPLIER_PROP_NAME);
        }
    }

    @Test
    void defaultTimerServiceSupplier()
    {
        context.conclude();

        final TimerServiceSupplier supplier = context.timerServiceSupplier();
        assertNotNull(supplier);

        final TimerService.TimerHandler timerHandler = mock(TimerService.TimerHandler.class);
        final TimerService timerService = supplier.newInstance(context.clusterClock().timeUnit(), timerHandler);

        assertNotNull(timerService);
        assertEquals(WheelTimerService.class, timerService.getClass());
    }

    @Test
    void explicitTimerServiceSupplier()
    {
        final TimerServiceSupplier supplier = (clusterClock, timerHandler) -> null;

        context.timerServiceSupplier(supplier);
        assertSame(supplier, context.timerServiceSupplier());

        context.conclude();

        assertSame(supplier, context.timerServiceSupplier());
    }

    @Test
    void rejectInvalidLogChannelParameters()
    {
        final String channelTermId = context.logChannel() + "|" + CommonContext.TERM_ID_PARAM_NAME + "=0";
        final String channelInitialTermId =
            context.logChannel() + "|" + CommonContext.INITIAL_TERM_ID_PARAM_NAME + "=0";
        final String channelTermOffset = context.logChannel() + "|" + CommonContext.TERM_OFFSET_PARAM_NAME + "=0";

        assertThrows(ConfigurationException.class, () -> context.clone().logChannel(channelTermId).conclude());
        assertThrows(ConfigurationException.class, () -> context.clone().logChannel(channelInitialTermId).conclude());
        assertThrows(ConfigurationException.class, () -> context.clone().logChannel(channelTermOffset).conclude());
    }

    @Test
    void defaultAuthorisationServiceSupplierReturnsADenyAllAuthorisationService()
    {
        assertSame(ALLOW_ONLY_BACKUP_QUERIES, DEFAULT_AUTHORISATION_SERVICE_SUPPLIER.get());
    }

    @Test
    void shouldUseDefaultAuthorisationServiceSupplierIfTheSystemPropertyIsNotSet()
    {
        assertNull(context.authorisationServiceSupplier());

        context.conclude();

        System.clearProperty(AUTHORISATION_SERVICE_SUPPLIER_PROP_NAME);
        assertSame(DEFAULT_AUTHORISATION_SERVICE_SUPPLIER, context.authorisationServiceSupplier());
    }

    @Test
    void shouldUseDefaultAuthorisationServiceSupplierIfTheSystemPropertyIsSetToEmptyValue()
    {
        System.setProperty(AUTHORISATION_SERVICE_SUPPLIER_PROP_NAME, "");
        try
        {
            assertNull(context.authorisationServiceSupplier());

            context.conclude();

            assertSame(DEFAULT_AUTHORISATION_SERVICE_SUPPLIER, context.authorisationServiceSupplier());
        }
        finally
        {
            System.clearProperty(AUTHORISATION_SERVICE_SUPPLIER_PROP_NAME);
        }
    }

    @Test
    void shouldInstantiateAuthorisationServiceSupplierBasedOnTheSystemProperty()
    {
        System.setProperty(AUTHORISATION_SERVICE_SUPPLIER_PROP_NAME, TestAuthorisationSupplier.class.getName());
        try
        {
            context.conclude();
            final AuthorisationServiceSupplier supplier = context.authorisationServiceSupplier();
            assertNotSame(DEFAULT_AUTHORISATION_SERVICE_SUPPLIER, supplier);
            assertInstanceOf(TestAuthorisationSupplier.class, supplier);
        }
        finally
        {
            System.clearProperty(AUTHORISATION_SERVICE_SUPPLIER_PROP_NAME);
        }
    }

    @Test
    void shouldUseProvidedAuthorisationServiceSupplierInstance()
    {
        final AuthorisationServiceSupplier providedSupplier = mock(AuthorisationServiceSupplier.class);
        context.authorisationServiceSupplier(providedSupplier);
        assertSame(providedSupplier, context.authorisationServiceSupplier());

        System.setProperty(AUTHORISATION_SERVICE_SUPPLIER_PROP_NAME, TestAuthorisationSupplier.class.getName());
        try
        {
            context.conclude();
            assertSame(providedSupplier, context.authorisationServiceSupplier());
        }
        finally
        {
            System.clearProperty(AUTHORISATION_SERVICE_SUPPLIER_PROP_NAME);
        }
    }

    @Test
    void shouldUseDefaultAuthenticatorSupplierIfTheSystemPropertyIsSetToEmptyValue()
    {
        System.setProperty(AUTHENTICATOR_SUPPLIER_PROP_NAME, "");
        try
        {
            assertNull(context.authenticatorSupplier());

            context.conclude();

            final AuthenticatorSupplier authenticatorSupplier = context.authenticatorSupplier();
            assertSame(DefaultAuthenticatorSupplier.INSTANCE, authenticatorSupplier);
        }
        finally
        {
            System.clearProperty(AUTHENTICATOR_SUPPLIER_PROP_NAME);
        }
    }

    @Test
    void shouldInstantiateAuthenticatorSupplierBasedOnTheSystemProperty()
    {
        System.setProperty(AUTHENTICATOR_SUPPLIER_PROP_NAME, TestAuthenticatorSupplier.class.getName());
        try
        {
            context.conclude();
            final AuthenticatorSupplier supplier = context.authenticatorSupplier();
            assertInstanceOf(TestAuthenticatorSupplier.class, supplier);
        }
        finally
        {
            System.clearProperty(AUTHENTICATOR_SUPPLIER_PROP_NAME);
        }
    }

    @Test
    void shouldUseProvidedAAuthenticatorSupplierInstance()
    {
        final AuthenticatorSupplier providedSupplier = mock(AuthenticatorSupplier.class);
        context.authenticatorSupplier(providedSupplier);
        assertSame(providedSupplier, context.authenticatorSupplier());

        System.setProperty(AUTHENTICATOR_SUPPLIER_PROP_NAME, TestAuthenticatorSupplier.class.getName());
        try
        {
            context.conclude();
            assertSame(providedSupplier, context.authenticatorSupplier());
        }
        finally
        {
            System.clearProperty(AUTHENTICATOR_SUPPLIER_PROP_NAME);
        }
    }

    @Test
    void writeAuthenticatorSupplierClassNameIntoTheMarkFile()
    {
        final TestAuthenticatorSupplier authenticatorSupplier = new TestAuthenticatorSupplier();
        final String authenticatorSupplierClassName = authenticatorSupplier.getClass().getName();
        context.authenticatorSupplier(authenticatorSupplier);

        context.conclude();

        final ClusterMarkFile markFile = context.clusterMarkFile();
        assertNotNull(markFile);
        final MarkFileHeaderDecoder decoder = markFile.decoder();
        decoder.sbeRewind();
        assertEquals(ClusterMarkFile.SEMANTIC_VERSION, decoder.version());
        assertEquals(CONSENSUS_MODULE, decoder.componentType());
        assertEquals(SystemUtil.getPid(), decoder.pid());
        assertEquals(SERVICE_ID, decoder.serviceId());
        assertEquals(context.aeron().context().aeronDirectoryName(), decoder.aeronDirectory());
        assertEquals(context.controlChannel(), decoder.controlChannel());
        assertEquals(context.ingressChannel(), decoder.ingressChannel());
        assertNotNull(decoder.serviceName());
        assertEquals(authenticatorSupplierClassName, decoder.authenticator());
    }

    @Test
    void shouldValidateModuleStateCounter()
    {
        context.moduleStateCounter(newCounter("moduleState", CONSENSUS_MODULE_ERROR_COUNT_TYPE_ID));
        assertThrows(ConfigurationException.class, context::conclude);
    }

    @Test
    void shouldValidateElectionStateCounter()
    {
        context.electionStateCounter(newCounter("electionState", CONSENSUS_MODULE_ERROR_COUNT_TYPE_ID));
        assertThrows(ConfigurationException.class, context::conclude);
    }

    @Test
    void shouldValidateClusterNodeRoleCounter()
    {
        context.clusterNodeRoleCounter(newCounter("clusterNodeRole", CONSENSUS_MODULE_ERROR_COUNT_TYPE_ID));
        assertThrows(ConfigurationException.class, context::conclude);
    }

    @Test
    void shouldValidateCommitPositionCounter()
    {
        context.commitPositionCounter(newCounter("commitPosition", CONSENSUS_MODULE_ERROR_COUNT_TYPE_ID));
        assertThrows(ConfigurationException.class, context::conclude);
    }

    @Test
    void shouldValidateControlToggleCounter()
    {
        context.controlToggleCounter(newCounter("controlToggle", CONSENSUS_MODULE_ERROR_COUNT_TYPE_ID));
        assertThrows(ConfigurationException.class, context::conclude);
    }

    @Test
    void shouldValidateSnapshotCounter()
    {
        context.snapshotCounter(newCounter("snapshot", CONSENSUS_MODULE_ERROR_COUNT_TYPE_ID));
        assertThrows(ConfigurationException.class, context::conclude);
    }

    @Test
    void shouldValidateTimedOutClientCounter()
    {
        context.timedOutClientCounter(newCounter("timedOut", CONSENSUS_MODULE_ERROR_COUNT_TYPE_ID));
        assertThrows(ConfigurationException.class, context::conclude);
    }

    @Test
    void shouldThrowIllegalStateExceptionIfAnActiveMarkFileExists()
    {
        final ConsensusModule.Context another = context.clone();
        context.conclude();

        final RuntimeException exception = assertThrowsExactly(RuntimeException.class, another::conclude);
        final Throwable cause = exception.getCause();
        assertInstanceOf(IllegalStateException.class, cause);
        assertEquals("active Mark file detected", cause.getMessage());
    }

    @ParameterizedTest
    @CsvSource({ "0, 1000", "5000,5000", "2000000000, 1000000001" })
    void startupCanvassTimeoutMustBeMultiplesOfTheLeaderHeartbeatTimeout(
        final long startupCanvassTimeoutNs, final long leaderHeartbeatTimeoutNs)
    {
        context.startupCanvassTimeoutNs(startupCanvassTimeoutNs)
            .leaderHeartbeatTimeoutNs(leaderHeartbeatTimeoutNs);

        final ClusterException exception = assertThrows(ClusterException.class, context::conclude);
        assertEquals("ERROR - startupCanvassTimeoutNs=" + startupCanvassTimeoutNs +
            " must be a multiple of leaderHeartbeatTimeoutNs=" + leaderHeartbeatTimeoutNs,
            exception.getMessage());
    }

    @Test
    void startupCanvassTimeoutMustCanBeSetToBeMultiplesOfTheLeaderHeartbeatTimeout()
    {
        context.startupCanvassTimeoutNs(TimeUnit.SECONDS.toNanos(30))
            .leaderHeartbeatTimeoutNs(TimeUnit.SECONDS.toNanos(5));

        context.conclude();
    }

    @Test
    void shouldThrowIfConductorInvokerModeIsNotUsed()
    {
        when(context.aeron().context().useConductorAgentInvoker()).thenReturn(false);
        assertThrows(ClusterException.class, () -> context.conclude());
    }

    @Test
    void shouldUseExplicitlyAssignedNameResolver()
    {
        final NameResolver nameResolver = mock(NameResolver.class);
        assertNull(context.nameResolver());

        context.nameResolver(nameResolver);
        assertSame(nameResolver, context.nameResolver());

        context.conclude();
        assertSame(nameResolver, context.nameResolver());
    }

    @Test
    void shouldUseDefaultNameResolver()
    {
        assertNull(context.nameResolver());

        context.conclude();

        assertSame(DefaultNameResolver.INSTANCE, context.nameResolver());
    }

    @Test
    void shouldUseCandidateTermIdFromClusterMarkFileIfNodeStateFileIsNew()
    {
        final TestClusterClock epochClock = new TestClusterClock(MILLISECONDS);
        final ClusterMarkFile clusterMarkFile = new ClusterMarkFile(
            new File(clusterDir, ClusterMarkFile.FILENAME),
            CONSENSUS_MODULE,
            ERROR_BUFFER_MIN_LENGTH,
            epochClock,
            1_000);
        final long existingCandidateTermId = 23;

        assertEquals(Aeron.NULL_VALUE, clusterMarkFile.candidateTermId());
        clusterMarkFile.encoder().candidateTermId(existingCandidateTermId);
        context.clusterMarkFile(clusterMarkFile);

        context.conclude();

        assertEquals(existingCandidateTermId, context.nodeStateFile().candidateTerm().candidateTermId());
    }

    @Test
    void shouldInitializeNameResolver()
    {
        final NameResolver nameResolver = mock(NameResolver.class);
        context.nameResolver(nameResolver);

        context.conclude();

        final ArgumentCaptor<CountersReader> readerCaptor = ArgumentCaptor.forClass(CountersReader.class);
        final ArgumentCaptor<CounterProvider> providerCaptor = ArgumentCaptor.forClass(CounterProvider.class);
        verify(nameResolver).init(readerCaptor.capture(), providerCaptor.capture());
        assertSame(context.aeron().countersReader(), readerCaptor.getValue());
        assertNotNull(providerCaptor.getValue());
        verifyNoMoreInteractions(nameResolver);
    }

    @Test
    void clusterDirectoryNameShouldMatchClusterDirWhenClusterDirSet() throws IOException
    {
        context.clusterDir(clusterDir);
        context.conclude();

        assertEquals(
            new File(context.clusterDirectoryName()).getCanonicalPath(), context.clusterDir().getCanonicalPath());
    }

    @Test
    void clusterDirectoryNameShouldMatchClusterDirWhenClusterDirectoryNameSet() throws IOException
    {
        context.clusterDir(null);
        context.clusterDirectoryName(clusterDir.getAbsolutePath());
        context.conclude();

        assertEquals(
            new File(context.clusterDirectoryName()).getCanonicalPath(), context.clusterDir().getCanonicalPath());
    }

    @Test
    @Disabled
    void concludeShouldCreateMarkFileDirSetViaSystemProperty(final @TempDir File tempDir)
    {
        final File rootDir = new File(tempDir, "root");
        final File markFileDir = new File(rootDir, "mark-file-dir");
        assertFalse(markFileDir.exists());

        System.setProperty(MARK_FILE_DIR_PROP_NAME, markFileDir.getAbsolutePath());
        try
        {
            assertSame(null, context.markFileDir());

            context.conclude();

            assertEquals(markFileDir, context.markFileDir());
            assertTrue(markFileDir.exists());
            assertTrue(new File(context.clusterDir(), ClusterMarkFile.LINK_FILENAME).exists());
        }
        finally
        {
            System.clearProperty(MARK_FILE_DIR_PROP_NAME);
        }
    }

    @Test
    void concludeShouldCreateMarkFileDirSetDirectly(final @TempDir File tempDir)
    {
        final File rootDir = new File(tempDir, "root");
        final File markFileDir = new File(rootDir, "mark-file-dir");
        assertFalse(markFileDir.exists());
        context.markFileDir(markFileDir);

        context.conclude();

        assertEquals(markFileDir, context.markFileDir());
        assertTrue(markFileDir.exists());
        assertTrue(new File(context.clusterDir(), ClusterMarkFile.LINK_FILENAME).exists());
    }

    @ParameterizedTest
    @ValueSource(booleans = { true, false })
    void shouldRemoveLinkIfMarkFileIsInClusterDir(final boolean isSet) throws IOException
    {
        final File markFileDir = isSet ? context.clusterDir() : null;

        context.markFileDir(markFileDir);
        final File oldLinkFile = new File(context.clusterDir(), ClusterMarkFile.LINK_FILENAME);
        assertTrue(oldLinkFile.createNewFile());
        assertTrue(oldLinkFile.exists());

        context.conclude();

        assertFalse(oldLinkFile.exists());
    }

    public static class TestAuthorisationSupplier implements AuthorisationServiceSupplier
    {
        public AuthorisationService get()
        {
            return new TestAuthorisationService();
        }
    }

    static class TestAuthorisationService implements AuthorisationService
    {
        public boolean isAuthorised(
            final int protocolId, final int actionId, final Object type, final byte[] encodedPrincipal)
        {
            return false;
        }
    }

    public static class TestAuthenticatorSupplier implements AuthenticatorSupplier
    {
        public Authenticator get()
        {
            return new TestAuthenticator();
        }
    }

    static class TestAuthenticator implements Authenticator
    {
        public void onConnectRequest(final long sessionId, final byte[] encodedCredentials, final long nowMs)
        {
        }

        public void onChallengeResponse(final long sessionId, final byte[] encodedCredentials, final long nowMs)
        {
        }

        public void onConnectedSession(final SessionProxy sessionProxy, final long nowMs)
        {
        }

        public void onChallengedSession(final SessionProxy sessionProxy, final long nowMs)
        {
        }
    }
}
