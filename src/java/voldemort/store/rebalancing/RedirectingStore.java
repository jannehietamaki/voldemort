/*
 * Copyright 2008-2009 LinkedIn, Inc
 * 
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 * 
 * http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package voldemort.store.rebalancing;

import java.util.List;

import voldemort.VoldemortException;
import voldemort.server.StoreRepository;
import voldemort.store.DelegatingStore;
import voldemort.store.Store;
import voldemort.store.StoreUtils;
import voldemort.store.metadata.MetadataStore;
import voldemort.store.metadata.MetadataStore.VoldemortState;
import voldemort.utils.ByteArray;
import voldemort.versioning.ObsoleteVersionException;
import voldemort.versioning.Version;
import voldemort.versioning.Versioned;

/**
 * The RedirectingStore extends {@link DelegatingStore}
 * <p>
 * if current server_state is {@link VoldemortState#REBALANCING_MASTER_SERVER} <br>
 * then before serving any client request do a remote get() call, put it locally
 * ignoring any {@link ObsoleteVersionException} and then serve the client
 * requests.
 * 
 * TODO: Deletes are not handled correctly right now, behavior is same as if the
 * node was down while deleting and came back later.
 * 
 * @author bbansal
 * 
 */
public class RedirectingStore extends DelegatingStore<ByteArray, byte[]> {

    private final MetadataStore metadata;
    private final StoreRepository storeRepository;

    public RedirectingStore(Store<ByteArray, byte[]> innerStore,
                            MetadataStore metadata,
                            StoreRepository storeRepository) {
        super(innerStore);
        this.metadata = metadata;
        this.storeRepository = storeRepository;
    }

    @Override
    public void put(ByteArray key, Versioned<byte[]> value) throws VoldemortException {
        if(MetadataStore.VoldemortState.REBALANCING_MASTER_SERVER.equals(metadata.getServerState())
           && checkKeyBelongsToStolenPartitions(key)) {
            // if I am rebalancing for this key, try to do remote get() , put it
            // locally first to get the correct version ignoring any
            // ObsoleteVersionExceptions.
            proxyPut(key);
        }

        getInnerStore().put(key, value);
    }

    @Override
    public List<Versioned<byte[]>> get(ByteArray key) throws VoldemortException {
        if(MetadataStore.VoldemortState.REBALANCING_MASTER_SERVER.equals(metadata.getServerState())
           && checkKeyBelongsToStolenPartitions(key)) {
            // if I am rebalancing for this key, try to do remote get() , put it
            // locally first to get the correct version ignoring any
            // ObsoleteVersionExceptions.
            proxyPut(key);
        }

        return getInnerStore().get(key);
    }

    /**
     * TODO : handle delete correctly <br>
     * option1: delete locally and on remote node as well, the issue is cursor
     * is open in READ_UNCOMMITED mode while rebalancing and can push the value
     * back.<br>
     * option2: keep it in separate slop store and apply deletes at the end of
     * rebalancing.<br>
     * option3: donot worry about deletes for now, voldemort in general have
     * this issue if node went down while delete will still keep the old
     * version.
     */
    @Override
    public boolean delete(ByteArray key, Version version) throws VoldemortException {
        StoreUtils.assertValidKey(key);
        return getInnerStore().delete(key, version);
    }

    protected boolean checkKeyBelongsToStolenPartitions(ByteArray key) {
        for(int partitionId: metadata.getRoutingStrategy(getName()).getPartitionList(key.get())) {
            if(metadata.getRebalancingPartitionList().contains(partitionId)) {
                return true;
            }
        }
        return false;
    }

    /**
     * performs back-door proxy get to {@link MetadataStore#getDonorNode()}
     * 
     * @param key
     * @return
     * @throws VoldemortException
     */
    private List<Versioned<byte[]>> proxyGet(ByteArray key) throws VoldemortException {

        if(!storeRepository.hasNodeStore(getName(), metadata.getRebalancingSlaveNodeId())) {
            throw new VoldemortException("Node Store not present in storeRepository for (store,nodeId) pair ("
                                         + getName()
                                         + ","
                                         + metadata.getRebalancingSlaveNodeId()
                                         + ").");
        }

        return storeRepository.getNodeStore(getName(), metadata.getRebalancingSlaveNodeId())
                              .get(key);
    }

    /**
     * In RebalancingStealer state put should be commited on stealer node. <br>
     * to follow voldemort version guarantees stealer <br>
     * node should query donor node and put that value (proxyValue) before
     * committing the value from client.
     * <p>
     * stealer node should ignore {@link ObsoleteVersionException} while
     * commiting proxyValue
     * 
     * 
     * @param key
     * @param value
     * @throws VoldemortException
     */
    private void proxyPut(ByteArray key) throws VoldemortException {
        List<Versioned<byte[]>> proxyValues = proxyGet(key);

        try {
            for(Versioned<byte[]> proxyValue: proxyValues) {
                getInnerStore().put(key, proxyValue);
            }
        } catch(ObsoleteVersionException e) {
            // ignore these
        }
    }
}
