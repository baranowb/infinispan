package org.infinispan.interceptors.distribution;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Stream;

import org.infinispan.commands.AbstractTopologyAffectedCommand;
import org.infinispan.commands.TopologyAffectedCommand;
import org.infinispan.commands.VisitableCommand;
import org.infinispan.commands.functional.ReadOnlyKeyCommand;
import org.infinispan.commands.functional.ReadOnlyManyCommand;
import org.infinispan.commands.read.AbstractDataCommand;
import org.infinispan.commands.read.GetAllCommand;
import org.infinispan.commands.read.GetCacheEntryCommand;
import org.infinispan.commands.read.GetKeyValueCommand;
import org.infinispan.commands.remote.ClusteredGetAllCommand;
import org.infinispan.commands.remote.ClusteredGetCommand;
import org.infinispan.commands.remote.GetKeysInGroupCommand;
import org.infinispan.commands.write.AbstractDataWriteCommand;
import org.infinispan.commands.write.ClearCommand;
import org.infinispan.commands.write.DataWriteCommand;
import org.infinispan.commands.write.ValueMatcher;
import org.infinispan.commons.CacheException;
import org.infinispan.container.entries.CacheEntry;
import org.infinispan.container.entries.InternalCacheEntry;
import org.infinispan.container.entries.InternalCacheValue;
import org.infinispan.container.entries.NullCacheEntry;
import org.infinispan.context.Flag;
import org.infinispan.context.InvocationContext;
import org.infinispan.context.impl.TxInvocationContext;
import org.infinispan.distribution.DistributionInfo;
import org.infinispan.distribution.DistributionManager;
import org.infinispan.distribution.Ownership;
import org.infinispan.distribution.RemoteValueRetrievedListener;
import org.infinispan.distribution.ch.ConsistentHash;
import org.infinispan.distribution.group.GroupManager;
import org.infinispan.factories.annotations.Inject;
import org.infinispan.factories.annotations.Start;
import org.infinispan.interceptors.BasicInvocationStage;
import org.infinispan.interceptors.InvocationStage;
import org.infinispan.interceptors.impl.ClusteringInterceptor;
import org.infinispan.interceptors.locking.ClusteringDependentLogic;
import org.infinispan.remoting.RemoteException;
import org.infinispan.remoting.RpcException;
import org.infinispan.remoting.responses.CacheNotFoundResponse;
import org.infinispan.remoting.responses.ExceptionResponse;
import org.infinispan.remoting.responses.Response;
import org.infinispan.remoting.responses.SuccessfulResponse;
import org.infinispan.remoting.responses.UnsuccessfulResponse;
import org.infinispan.remoting.rpc.ResponseMode;
import org.infinispan.remoting.rpc.RpcManager;
import org.infinispan.remoting.rpc.RpcOptions;
import org.infinispan.remoting.transport.Address;
import org.infinispan.statetransfer.OutdatedTopologyException;
import org.infinispan.topology.CacheTopology;
import org.infinispan.transaction.xa.GlobalTransaction;
import org.infinispan.util.concurrent.CompletableFutures;
import org.infinispan.util.logging.Log;
import org.infinispan.util.logging.LogFactory;

/**
 * Base class for distribution of entries across a cluster.
 *
 * @author Manik Surtani
 * @author Mircea.Markus@jboss.com
 * @author Pete Muir
 * @author Dan Berindei <dan@infinispan.org>
 */
public abstract class BaseDistributionInterceptor extends ClusteringInterceptor {
   protected DistributionManager dm;

   protected ClusteringDependentLogic cdl;
   protected RemoteValueRetrievedListener rvrl;
   protected boolean isL1Enabled;
   private GroupManager groupManager;

   private static final Log log = LogFactory.getLog(BaseDistributionInterceptor.class);
   private static final boolean trace = log.isTraceEnabled();

   @Override
   protected Log getLog() {
      return log;
   }

   @Inject
   public void injectDependencies(DistributionManager distributionManager, ClusteringDependentLogic cdl,
         RemoteValueRetrievedListener rvrl, GroupManager groupManager) {
      this.dm = distributionManager;
      this.cdl = cdl;
      this.rvrl = rvrl;
      this.groupManager = groupManager;
   }


   @Start
   public void configure() {
      // Can't rely on the super injectConfiguration() to be called before our injectDependencies() method2
      isL1Enabled = cacheConfiguration.clustering().l1().enabled();
   }

   @Override
   public final BasicInvocationStage visitGetKeysInGroupCommand(InvocationContext ctx, GetKeysInGroupCommand command)
         throws Throwable {
      final String groupName = command.getGroupName();
      if (command.isGroupOwner()) {
         //don't go remote if we are an owner.
         return invokeNext(ctx, command);
      }
      CompletableFuture<Map<Address, Response>> future = rpcManager.invokeRemotelyAsync(
            Collections.singleton(groupManager.getPrimaryOwner(groupName)), command,
            rpcManager.getDefaultRpcOptions(true));
      return invokeNextAsync(ctx, command, future.thenAccept(responses -> {
         if (!responses.isEmpty()) {
            Response response = responses.values().iterator().next();
            if (response instanceof SuccessfulResponse) {
               //noinspection unchecked
               List<CacheEntry> cacheEntries = (List<CacheEntry>) ((SuccessfulResponse) response).getResponseValue();
               for (CacheEntry entry : cacheEntries) {
                  entryFactory.wrapExternalEntry(ctx, entry.getKey(), entry, false);
               }
            }
         }
      }));
   }

   @Override
   public final BasicInvocationStage visitClearCommand(InvocationContext ctx, ClearCommand command) throws Throwable {
      if (ctx.isOriginLocal() && !isLocalModeForced(command)) {
         RpcOptions rpcOptions = rpcManager.getRpcOptionsBuilder(
               isSynchronous(command) ? ResponseMode.SYNCHRONOUS_IGNORE_LEAVERS : ResponseMode.ASYNCHRONOUS).build();
         return invokeNextAsync(ctx, command, rpcManager.invokeRemotelyAsync(null, command, rpcOptions));
      }
      return invokeNext(ctx, command);
   }

   protected final CompletableFuture<Void> remoteGet(InvocationContext ctx, AbstractTopologyAffectedCommand command,
                                                     Object key, boolean isWrite) {
      CacheTopology cacheTopology = checkTopologyId(command);
      int topologyId = cacheTopology.getTopologyId();
      ConsistentHash readCH = cacheTopology.getReadConsistentHash();

      DistributionInfo info = new DistributionInfo(key, readCH, rpcManager.getAddress());
      if (info.ownership() != Ownership.NON_OWNER) {
         if (trace) {
            log.tracef("Key %s is local, skipping remote get. Command topology is %d, current topology is %d",
                  key, command.getTopologyId(), topologyId);
         }
         // The topology has changed between EWI and BDI, let's retry
         if (command.getTopologyId() == topologyId) {
            throw new IllegalStateException();
         }
         throw new OutdatedTopologyException(topologyId);
      }
      if (trace) {
         log.tracef("Perform remote get for key %s. currentTopologyId=%s, owners=%s",
            key, topologyId, info.owners());
      }

      ClusteredGetCommand getCommand = cf.buildClusteredGetCommand(key, command.getFlagsBitSet());
      getCommand.setTopologyId(topologyId);
      getCommand.setWrite(isWrite);

      return rpcManager.invokeRemotelyAsync(info.owners(), getCommand, staggeredOptions).thenAccept(responses -> {
         for (Response r : responses.values()) {
            if (r instanceof SuccessfulResponse) {
               SuccessfulResponse response = (SuccessfulResponse) r;
               Object responseValue = response.getResponseValue();
               if (responseValue == null) {
                  if (rvrl != null) {
                     rvrl.remoteValueNotFound(key);
                  }
                  entryFactory.wrapExternalEntry(ctx, key, NullCacheEntry.getInstance(), isWrite);
                  return;
               }
               InternalCacheEntry ice = ((InternalCacheValue) responseValue).toInternalCacheEntry(key);
               if (rvrl != null) {
                  rvrl.remoteValueFound(ice);
               }
               entryFactory.wrapExternalEntry(ctx, key, ice, isWrite);
               return;
            }
         }
         // If this node has topology higher than some of the nodes and the nodes could not respond
         // with the remote entry, these nodes are blocking the response and therefore we can get only timeouts.
         // Therefore, if we got here it means that we have lower topology than some other nodes and we can wait
         // for it in StateTransferInterceptor and retry the read later.
         // TODO: These situations won't happen as soon as we'll implement 4-phase topology change in ISPN-5021
         throw new OutdatedTopologyException("Did not get any successful response, got " + responses);
      });
   }

   protected final BasicInvocationStage handleNonTxWriteCommand(InvocationContext ctx, AbstractDataWriteCommand command)
         throws Throwable {
      Object key = command.getKey();
      CacheEntry entry = ctx.lookupEntry(key);

      if (isLocalModeForced(command)) {
         if (entry == null) {
            entryFactory.wrapExternalEntry(ctx, key, null, true);
         }
         return invokeNext(ctx, command);
      }

      if (entry == null) {
         DistributionInfo info = new DistributionInfo(key, checkTopologyId(command).getWriteConsistentHash(), rpcManager.getAddress());
         boolean load = shouldLoad(ctx, command, info);
         if (info.isPrimary()) {
            throw new IllegalStateException("Primary owner in writeCH should always be an owner in readCH as well.");
         } else if (ctx.isOriginLocal()) {
            return invokeRemotely(command, info.primary());
         } else {
            if (load) {
               CompletableFuture<?> getFuture = remoteGet(ctx, command, command.getKey(), true);
               return invokeNextAsync(ctx, command, getFuture);
            } else {
               entryFactory.wrapExternalEntry(ctx, key, null, true);
               return invokeNext(ctx, command);
            }
         }
      } else {
         DistributionInfo info = new DistributionInfo(key, checkTopologyId(command).getWriteConsistentHash(), rpcManager.getAddress());
         if (info.isPrimary()) {
            return invokeNext(ctx, command).thenCompose(this::primaryReturnHandler);
         } else if (ctx.isOriginLocal()) {
            return invokeRemotely(command, info.primary());
         } else {
            return invokeNext(ctx, command);
         }
      }
   }

   private boolean shouldLoad(InvocationContext ctx, AbstractDataWriteCommand command, DistributionInfo info) {
      if (!command.hasFlag(Flag.SKIP_REMOTE_LOOKUP)) {
         VisitableCommand.LoadType loadType = command.loadType();
         switch (loadType) {
            case DONT_LOAD:
               return false;
            case OWNER:
               switch (info.ownership()) {
                  case PRIMARY:
                     return true;
                  case BACKUP:
                     return !ctx.isOriginLocal();
                  case NON_OWNER:
                     return false;
                  default:
                     throw new IllegalStateException();
               }
            case PRIMARY:
               return info.isPrimary();
            default:
               throw new IllegalStateException();
         }
      } else {
         return false;
      }
   }

   private BasicInvocationStage invokeRemotely(DataWriteCommand command, Address primaryOwner) {
      if (trace) log.tracef("I'm not the primary owner, so sending the command to the primary owner(%s) in order to be forwarded", primaryOwner);
      boolean isSyncForwarding = isSynchronous(command) || command.isReturnValueExpected();

      CompletableFuture<Map<Address, Response>> remoteInvocation;
      try {
         remoteInvocation = rpcManager.invokeRemotelyAsync(Collections.singletonList(primaryOwner), command,
               rpcManager.getDefaultRpcOptions(isSyncForwarding));
      } catch (Throwable t) {
         command.setValueMatcher(command.getValueMatcher().matcherForRetry());
         throw t;
      }
      if (isSyncForwarding) {
         return returnWithAsync(remoteInvocation.handle((responses, t) -> {
            command.setValueMatcher(command.getValueMatcher().matcherForRetry());
            CompletableFutures.rethrowException(t);

            Object primaryResult = getResponseFromPrimaryOwner(primaryOwner, responses);
            command.updateStatusFromRemoteResponse(primaryResult);
            return primaryResult;
         }));
      } else {
         return returnWith(null);
      }
   }

   private BasicInvocationStage primaryReturnHandler(BasicInvocationStage ignored, InvocationContext ctx, VisitableCommand visitableCommand, Object localResult) {
      DataWriteCommand command = (DataWriteCommand) visitableCommand;
      if (!command.isSuccessful()) {
         if (trace) log.tracef("Skipping the replication of the conditional command as it did not succeed on primary owner (%s).", command);
         return returnWith(localResult);
      }
      // check if a single owner has been configured and the target for the key is the local address
      boolean isSingleOwnerAndLocal = cacheConfiguration.clustering().hash().numOwners() == 1;
      if (isSingleOwnerAndLocal) {
         return returnWith(localResult);
      }
      ConsistentHash writeCH = checkTopologyId(command).getWriteConsistentHash();
      List<Address> recipients = writeCH.isReplicated() ? null : writeCH.locateOwners(command.getKey());

      // Cache the matcher and reset it if we get OOTE (or any other exception) from backup
      ValueMatcher originalMatcher = command.getValueMatcher();
      // Ignore the previous value on the backup owners
      command.setValueMatcher(ValueMatcher.MATCH_ALWAYS);
      RpcOptions rpcOptions = determineRpcOptionsForBackupReplication(rpcManager, isSynchronous(command), recipients);
      CompletableFuture<Map<Address, Response>> remoteInvocation =
            rpcManager.invokeRemotelyAsync(recipients, command, rpcOptions);
      return returnWithAsync(remoteInvocation.handle((responses, t) -> {
         // Switch to the retry policy, in case the primary owner changed and the write already succeeded on the new primary
         command.setValueMatcher(originalMatcher.matcherForRetry());
         CompletableFutures.rethrowException(t instanceof RemoteException ? t.getCause() : t);
         return localResult;
      }));
   }

   private RpcOptions determineRpcOptionsForBackupReplication(RpcManager rpc, boolean isSync, List<Address> recipients) {
      RpcOptions options;
      if (isSync) {
         // If no recipients, means a broadcast, so we can ignore leavers
         if (recipients == null) {
            options = rpc.getRpcOptionsBuilder(ResponseMode.SYNCHRONOUS_IGNORE_LEAVERS).build();
         } else {
            options = rpc.getDefaultRpcOptions(true);
         }
      } else {
         options = rpc.getDefaultRpcOptions(false);
      }
      return options;
   }

   private Object getResponseFromPrimaryOwner(Address primaryOwner, Map<Address, Response> addressResponseMap) {
      Response fromPrimaryOwner = addressResponseMap.get(primaryOwner);
      if (fromPrimaryOwner == null) {
         if (trace) log.tracef("Primary owner %s returned null", primaryOwner);
         return null;
      }
      if (fromPrimaryOwner.isSuccessful()) {
         return ((SuccessfulResponse) fromPrimaryOwner).getResponseValue();
      }

      if (addressResponseMap.get(primaryOwner) instanceof CacheNotFoundResponse) {
         // This means the cache wasn't running on the primary owner, so the command wasn't executed.
         // We throw an OutdatedTopologyException, StateTransferInterceptor will catch the exception and
         // it will then retry the command.
         throw new OutdatedTopologyException("Cache is no longer running on primary owner " + primaryOwner);
      }

      Throwable cause = fromPrimaryOwner instanceof ExceptionResponse ? ((ExceptionResponse)fromPrimaryOwner).getException() : null;
      throw new CacheException("Got unsuccessful response from primary owner: " + fromPrimaryOwner, cause);
   }

   @Override
   public BasicInvocationStage visitGetAllCommand(InvocationContext ctx, GetAllCommand command) throws Throwable {
      if (command.hasFlag(Flag.CACHE_MODE_LOCAL) || command.hasFlag(Flag.SKIP_REMOTE_LOOKUP)) {
         return invokeNext(ctx, command);
      }

      if (!ctx.isOriginLocal()) {
         for (Object key : command.getKeys()) {
            if (ctx.lookupEntry(key) == null) {
               return handleMissingEntryOnRead(command);
            }
         }
         return invokeNext(ctx, command);
      }

      CacheTopology cacheTopology = checkTopologyId(command);
      ConsistentHash ch = cacheTopology.getReadConsistentHash();

      Map<Address, List<Object>> requestedKeys = getKeysByOwner(ctx, command.getKeys(), ch, null);
      if (requestedKeys.isEmpty()) {
         return invokeNext(ctx, command);
      }

      GlobalTransaction gtx = ctx.isInTxScope() ? ((TxInvocationContext) ctx).getGlobalTransaction() : null;
      CompletableFutureWithCounter allFuture = new CompletableFutureWithCounter(requestedKeys.size());

      for (Map.Entry<Address, List<Object>> pair : requestedKeys.entrySet()) {
         List<Object> keys = pair.getValue();
         ClusteredGetAllCommand clusteredGetAllCommand = cf.buildClusteredGetAllCommand(keys, command.getFlagsBitSet(), gtx);
         rpcManager.invokeRemotelyAsync(Collections.singleton(pair.getKey()), clusteredGetAllCommand, defaultSyncOptions).whenComplete((responseMap, throwable) -> {
            if (throwable != null) {
               allFuture.completeExceptionally(throwable);
            }
            Response response = getSingleSuccessfulResponseOrFail(responseMap, allFuture);
            if (response == null) return;
            Object responseValue = ((SuccessfulResponse) response).getResponseValue();
            if (responseValue instanceof InternalCacheValue[]) {
               InternalCacheValue[] values = (InternalCacheValue[]) responseValue;
               int counterValue;
               synchronized (allFuture) {
                  for (int i = 0; i < keys.size(); ++i) {
                     Object key = keys.get(i);
                     InternalCacheValue value = values[i];
                     CacheEntry entry = value == null ? NullCacheEntry.getInstance() : value.toInternalCacheEntry(key);
                     entryFactory.wrapExternalEntry(ctx, key, entry, false);
                  }
                  counterValue = --allFuture.counter;
               }
               // complete the future after sync block!
               if (counterValue == 0) {
                  allFuture.complete(null);
               }
            } else {
               allFuture.completeExceptionally(new IllegalStateException("Unexpected response value: " + responseValue));
            }
         });
      }
      return invokeNextAsync(ctx, command, allFuture);
   }

   @Override
   public BasicInvocationStage visitReadOnlyManyCommand(InvocationContext ctx, ReadOnlyManyCommand command) throws Throwable {
      // We cannot merge this method with visitGetAllCommand because this can't wrap entries into context
      if (command.hasFlag(Flag.CACHE_MODE_LOCAL) || command.hasFlag(Flag.SKIP_REMOTE_LOOKUP)) {
         return handleLocalOnlyReadOnlyManyCommand(ctx, command);
      }

      CacheTopology cacheTopology = checkTopologyId(command);
      if (!ctx.isOriginLocal()) {
         return handleRemoteReadOnlyManyCommand(ctx, command);
      }
      if (command.getKeys().isEmpty()) {
         return returnWith(Stream.empty());
      }

      ConsistentHash ch = cacheTopology.getReadConsistentHash();
      int estimateForOneNode = 2 * command.getKeys().size() / ch.getMembers().size();
      List<Object> availableKeys = new ArrayList<>(estimateForOneNode);
      Map<Address, List<Object>> requestedKeys = getKeysByOwner(ctx, command.getKeys(), ch, availableKeys);

      // TODO: while this works in a non-blocking way, the returned stream is not lazy as the functional
      // contract suggests. Traversable is also not honored as it is executed only locally on originator.
      // On FutureMode.ASYNC, there should be one command per target node going from the top level
      // to allow retries in StateTransferInterceptor in case of topology change.
      MergingCompletableFuture<Object> allFuture = new MergingCompletableFuture<>(
         ctx, requestedKeys.size() + (availableKeys.isEmpty() ? 0 : 1),
         new Object[command.getKeys().size()], Arrays::stream);

      handleLocallyAvailableKeys(ctx, command, availableKeys, allFuture);
      int pos = availableKeys.size();
      for (Map.Entry<Address, List<Object>> addressKeys : requestedKeys.entrySet()) {
         List<Object> keys = addressKeys.getValue();
         remoteReadOnlyMany(addressKeys.getKey(), keys, command.getFunction(), allFuture, pos);
         pos += keys.size();
      }
      return returnWithAsync(allFuture);
   }

   private InvocationStage handleLocalOnlyReadOnlyManyCommand(InvocationContext ctx, ReadOnlyManyCommand command) {
      for (Object key : command.getKeys()) {
         if (ctx.lookupEntry(key) == null) {
            entryFactory.wrapExternalEntry(ctx, key, NullCacheEntry.getInstance(), false);
         }
      }
      return invokeNext(ctx, command);
   }

   private BasicInvocationStage handleRemoteReadOnlyManyCommand(InvocationContext ctx, ReadOnlyManyCommand command) {
      for (Object key : command.getKeys()) {
         if (ctx.lookupEntry(key) == null) {
            return handleMissingEntryOnRead(command);
         }
      }
      return invokeNext(ctx, command).thenApply((rCtx, rCommand, rv) -> {
         // apply function happens here
         return ((Stream) rv).toArray();
      });
   }

   private void remoteReadOnlyMany(Address owner, List<Object> keys, Function function, MergingCompletableFuture<Object> allFuture, int destinationIndex) {
      ReadOnlyManyCommand remoteCommand = cf.buildReadOnlyManyCommand(keys, function);
      rpcManager.invokeRemotelyAsync(Collections.singleton(owner), remoteCommand, defaultSyncOptions)
         .whenComplete((responseMap, throwable) -> {
            if (throwable != null) {
               allFuture.completeExceptionally(throwable);
            }
            Response response = getSingleSuccessfulResponseOrFail(responseMap, allFuture);
            if (response == null) return;
            Object responseValue = ((SuccessfulResponse) response).getResponseValue();
            if (responseValue instanceof Object[]) {
               Object[] values = (Object[]) responseValue;
               System.arraycopy(values, 0, allFuture.results, destinationIndex, values.length);
               allFuture.countDown();
            } else {
               allFuture.completeExceptionally(new IllegalStateException("Unexpected response value " + responseValue));
            }
         });
   }

   private void handleLocallyAvailableKeys(InvocationContext ctx, ReadOnlyManyCommand command, List<Object> availableKeys, MergingCompletableFuture<Object> allFuture) {
      if (availableKeys.isEmpty()) {
         return;
      }
      ReadOnlyManyCommand localCommand = cf.buildReadOnlyManyCommand(availableKeys, command.getFunction());
      invokeNext(ctx, localCommand).compose((stage, rCtx, rCommand, rv, throwable) -> {
         if (throwable != null) {
            allFuture.completeExceptionally(throwable);
         } else {
            try {
               Supplier<ArrayIterator> supplier = () -> new ArrayIterator(allFuture.results);
               BiConsumer<ArrayIterator, Object> consumer = ArrayIterator::add;
               BiConsumer<ArrayIterator, ArrayIterator> combiner = ArrayIterator::combine;
               ((Stream) rv).collect(supplier, consumer, combiner);
               allFuture.countDown();
            } catch (Throwable t) {
               allFuture.completeExceptionally(t);
            }
         }
         return returnWithAsync(allFuture);
      });
   }

   private Map<Address, List<Object>> getKeysByOwner(InvocationContext ctx, Collection<?> keys, ConsistentHash ch, List<Object> availableKeys) {
      Map<Address, List<Object>> requestedKeys = new HashMap<>(ch.getMembers().size());
      int estimateForOneNode = 2 * keys.size() / ch.getMembers().size();
      for (Object key : keys) {
         CacheEntry entry = ctx.lookupEntry(key);
         if (entry == null) {
            List<Address> owners = ch.locateOwners(key);
            // Let's try to minimize the number of messages by preferring owner to which we've already
            // decided to send message
            boolean foundExisting = false;
            for (Address address : owners) {
               if (address.equals(rpcManager.getAddress())) {
                  throw new IllegalStateException("Entry should be always wrapped!");
               }
               List<Object> list = requestedKeys.get(address);
               if (list != null) {
                  list.add(key);
                  foundExisting = true;
                  break;
               }
            }
            if (!foundExisting) {
               List<Object> list = new ArrayList<>(estimateForOneNode);
               list.add(key);
               requestedKeys.put(owners.get(0), list);
            }
         } else if (availableKeys != null) {
            availableKeys.add(key);
         }
      }
      return requestedKeys;
   }

   protected Response getSingleSuccessfulResponseOrFail(Map<Address, Response> responseMap, CompletableFuture<?> future) {
      Iterator<Response> it = responseMap.values().iterator();
      if (!it.hasNext()) {
         future.completeExceptionally(new RpcException("Expected one response"));
         return null;
      } else {
         Response response = it.next();
         if (it.hasNext()) {
            future.completeExceptionally(new IllegalStateException("Too many responses " + responseMap));
            return null;
         }
         if (!response.isSuccessful()) {
            // CHECKME: The command is sent with current topology and deferred until the node gets our topology;
            // therefore if it returns unsuccessful response we can assume that there is a newer topology
            future.completeExceptionally(new OutdatedTopologyException("Remote node has higher topology, response " + response));
            return null;
         }
         return response;
      }
   }

   private static class ArrayIterator {
      private final Object[] array;
      private int pos = 0;

      public ArrayIterator(Object[] array) {
         this.array = array;
      }

      public void add(Object item) {
         array[pos] = item;
         ++pos;
      }

      public void combine(ArrayIterator other) {
         throw new UnsupportedOperationException("The stream is not supposed to be parallel");
      }
   }

   // This class sis very similar to CountDownCompletableFuture but it expect external synchronization,
   // That comes handy when we have to sync anyway on different item, for example the context
   private static class CompletableFutureWithCounter extends CompletableFuture<Void> {
      private int counter;

      public CompletableFutureWithCounter(int counter) {
         this.counter = counter;
      }
   }

   protected static class CountDownCompletableFuture extends CompletableFuture<Object> {
      protected final InvocationContext ctx;
      protected final AtomicInteger counter;

      public CountDownCompletableFuture(InvocationContext ctx, int participants) {
         if (trace) log.tracef("Creating shortcut countdown with %d participants", participants);
         this.ctx = ctx;
         this.counter = new AtomicInteger(participants);
      }

      public void countDown() {
         if (counter.decrementAndGet() == 0) {
            complete(result());
         }
      }

      public void increment() {
         int preValue = counter.getAndIncrement();
         if (preValue == 0) {
            throw new IllegalStateException();
         }
      }

      protected Object result() {
         return null;
      }
   }

   protected static class MergingCompletableFuture<T> extends CountDownCompletableFuture {
      private final Function<T[], Object> transform;
      protected final T[] results;

      public MergingCompletableFuture(InvocationContext ctx, int participants, T[] results, Function<T[], Object> transform) {
         super(ctx, participants);
         // results can be null if the command has flag IGNORE_RETURN_VALUE
         this.results = results;
         this.transform = transform;
      }

      @Override
      protected Object result() {
         return transform == null || results == null ? null : transform.apply(results);
      }
   }

   private BasicInvocationStage visitGetCommand(InvocationContext ctx, AbstractDataCommand command) throws Throwable {
      Object key = command.getKey();
      CacheEntry entry = ctx.lookupEntry(key);

      if (entry == null) {
         if (ctx.isOriginLocal()) {
            if (readNeedsRemoteValue(ctx, command)) {
               return invokeNextAsync(ctx, command, remoteGet(ctx, command, command.getKey(), false));
            } else {
               return returnWith(null);
            }
         } else {
            return handleMissingEntryOnRead(command);
         }
      }
      return invokeNext(ctx, command);
   }

   private BasicInvocationStage handleMissingEntryOnRead(TopologyAffectedCommand command) {
      // If we have the entry in context it means that we are read owners, so we don't have to check the topology
      CacheTopology cacheTopology = stateTransferManager.getCacheTopology();
      int currentTopologyId = cacheTopology.getTopologyId();
      int cmdTopology = command.getTopologyId();
      if (cmdTopology < currentTopologyId) {
         return returnWith(UnsuccessfulResponse.INSTANCE);
      } else {
         // If cmdTopology > currentTopologyId: the topology of this node is outdated
         // TODO: This situation won't happen as soon as we'll implement 4-phase topology change in ISPN-5021
         // (then, Tx.readCH and T(x+1).readCH will always have common subset of nodes so we'll be safe here
         // to return UnsuccessfulResponse
         // If cmdTopology == currentTopologyId: between STI and BDI this node had different topology, therefore
         // the entry was not loaded into context. Retry again locally.
         throw new OutdatedTopologyException(cmdTopology);
      }
   }

   @Override
   public BasicInvocationStage visitGetKeyValueCommand(InvocationContext ctx, GetKeyValueCommand command)
         throws Throwable {
      return visitGetCommand(ctx, command);
   }

   @Override
   public BasicInvocationStage visitGetCacheEntryCommand(InvocationContext ctx,
         GetCacheEntryCommand command) throws Throwable {
      return visitGetCommand(ctx, command);
   }

   @Override
   public BasicInvocationStage visitReadOnlyKeyCommand(InvocationContext ctx, ReadOnlyKeyCommand command)
         throws Throwable {
      Object key = command.getKey();
      if (ctx.lookupEntry(key) != null) {
         // the entry is owned locally (it is NullCacheEntry if it was not found), no need to go remote
         return invokeNext(ctx, command);
      }
      if (!ctx.isOriginLocal()) {
         return handleMissingEntryOnRead(command);
      }
      if (readNeedsRemoteValue(ctx, command)) {
         CacheTopology cacheTopology = checkTopologyId(command);
         List<Address> owners = cacheTopology.getReadConsistentHash().locateOwners(key);
         if (trace)
            log.tracef("Doing a remote get for key %s in topology %d to %s", key, cacheTopology.getTopologyId(), owners);

         // make sure that the command topology is set to the value according which we route it
         command.setTopologyId(cacheTopology.getTopologyId());

         CompletableFuture<Map<Address, Response>> rpc = rpcManager.invokeRemotelyAsync(owners, command,
               staggeredOptions);
         return returnWithAsync(rpc.thenApply(
               (responseMap) -> {
                  for (Response rsp : responseMap.values()) {
                     if (rsp.isSuccessful()) {
                        return ((SuccessfulResponse) rsp).getResponseValue();
                     }
                  }
                  // On receiver side the command topology id is checked and if it's too new, the command is delayed.
                  // We can assume that we miss successful response only because the owners already have new topology
                  // in which they're not owners - we'll wait for this topology, then.
                  throw new OutdatedTopologyException("We haven't found an owner");
               }));
      } else {
         // This has LOCAL flags, just wrap NullCacheEntry and let the command run
         entryFactory.wrapExternalEntry(ctx, key, NullCacheEntry.getInstance(), false);
         return invokeNext(ctx, command);
      }
   }

   protected CacheTopology checkTopologyId(TopologyAffectedCommand command) {
      CacheTopology cacheTopology = stateTransferManager.getCacheTopology();
      int currentTopologyId = cacheTopology.getTopologyId();
      int cmdTopology = command.getTopologyId();
      if (currentTopologyId != cmdTopology && cmdTopology != -1) {
         throw new OutdatedTopologyException("Cache topology changed while the command was executing: expected " +
            cmdTopology + ", got " + currentTopologyId);
      }
      if (trace) {
         log.tracef("Current topology %d, command topology %d", currentTopologyId, cmdTopology);
      }
      return cacheTopology;
   }

   /**
    * @return {@code true} if the value is not available on the local node and a read command is allowed to
    * fetch it from a remote node. Does not check if the value is already in the context.
    */
   protected boolean readNeedsRemoteValue(InvocationContext ctx, AbstractDataCommand command) {
      return ctx.isOriginLocal() && !command.hasFlag(Flag.CACHE_MODE_LOCAL) &&
            !command.hasFlag(Flag.SKIP_REMOTE_LOOKUP);
   }
}
