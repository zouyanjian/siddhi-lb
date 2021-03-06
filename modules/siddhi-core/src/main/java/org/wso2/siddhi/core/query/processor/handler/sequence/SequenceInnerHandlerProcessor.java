/*
*  Copyright (c) 2005-2010, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
*
*  WSO2 Inc. licenses this file to you under the Apache License,
*  Version 2.0 (the "License"); you may not use this file except
*  in compliance with the License.
*  You may obtain a copy of the License at
*
*    http://www.apache.org/licenses/LICENSE-2.0
*
* Unless required by applicable law or agreed to in writing,
* software distributed under the License is distributed on an
* "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
* KIND, either express or implied.  See the License for the
* specific language governing permissions and limitations
* under the License.
*/
package org.wso2.siddhi.core.query.processor.handler.sequence;

import org.apache.log4j.Logger;
import org.wso2.siddhi.core.config.SiddhiContext;
import org.wso2.siddhi.core.event.AtomicEvent;
import org.wso2.siddhi.core.event.StateEvent;
import org.wso2.siddhi.core.event.StreamEvent;
import org.wso2.siddhi.core.event.in.InStateEvent;
import org.wso2.siddhi.core.event.management.PersistenceManagementEvent;
import org.wso2.siddhi.core.persistence.PersistenceObject;
import org.wso2.siddhi.core.persistence.PersistenceStore;
import org.wso2.siddhi.core.persistence.Persister;
import org.wso2.siddhi.core.query.QueryElement;
import org.wso2.siddhi.core.query.processor.filter.FilterProcessor;
import org.wso2.siddhi.core.query.processor.handler.InnerHandlerProcessor;
import org.wso2.siddhi.core.query.projector.QueryProjector;
import org.wso2.siddhi.core.statemachine.sequence.OrSequenceState;
import org.wso2.siddhi.core.statemachine.sequence.SequenceState;
import org.wso2.siddhi.core.util.statelist.StateList;
import org.wso2.siddhi.core.util.statelist.StateListGrid;

import java.util.Collection;

public class SequenceInnerHandlerProcessor
        implements InnerHandlerProcessor, QueryElement, Persister {
    static final Logger log = Logger.getLogger(SequenceInnerHandlerProcessor.class);
    protected int complexEventSize;
    protected SequenceState state;
    protected SequenceState nextState;
    protected FilterProcessor filterProcessor;
    protected StateList<StateEvent> currentEvents;
    protected StateList<StateEvent> nextEvents;
    //    private final boolean first;
    protected final int currentState;
    protected String nodeId;
    protected PersistenceStore persistenceStore;
    protected long within = -1;
    protected boolean distributedProcessing = false;
    protected SiddhiContext siddhiContext;
    private QueryProjector queryProjector;

    public SequenceInnerHandlerProcessor(SequenceState state,
                                         FilterProcessor filterProcessor,
                                         int complexEventSize,
                                         SiddhiContext siddhiContext,String nodeId) {
        this.state = state;
        this.nextState = state.getNextState();
        this.currentState = state.getStateNumber();
        this.complexEventSize = complexEventSize;
        this.filterProcessor = filterProcessor;
        this.distributedProcessing = siddhiContext.isDistributedProcessing();
        this.siddhiContext = siddhiContext;
        this.nodeId=nodeId;
        if (distributedProcessing) {
            currentEvents = new StateListGrid(nodeId + "-currentState", siddhiContext);
            nextEvents = new StateListGrid(nodeId + "-nextEvents", siddhiContext);
        } else {
            currentEvents = new StateList<StateEvent>();
            nextEvents = new StateList<StateEvent>();
        }
    }

    public void init() {
        if (state.isFirst()) {
            //first event
            if (distributedProcessing) {
                if (!nextEvents.isInited()) {
                    addToNextEvents(new InStateEvent(new StreamEvent[complexEventSize], siddhiContext.getGlobalIndexGenerator().getNewIndex()));
                }
            } else {
                addToNextEvents(new InStateEvent(new StreamEvent[complexEventSize]));
            }
        }
    }

    protected void reInit() {
        if (state.isFirst()) {
            //first event
            if (distributedProcessing) {
                addToNextEvents(new InStateEvent(new StreamEvent[complexEventSize], siddhiContext.getGlobalIndexGenerator().getNewIndex()));
            } else {
                addToNextEvents(new InStateEvent(new StreamEvent[complexEventSize]));
            }
        }
    }

    @Override
    public void process(StreamEvent streamEvent) {
        sendForProcess(streamEvent);
        reInit();
    }

    protected void sendForProcess(StreamEvent event) {
        if (log.isDebugEnabled()) {
            log.debug("sr state=" + currentState + " event=" + event + " ||eventBank=" + currentEvents);
        }
        for (StateEvent currentEvent : getCollection()) {
            if (isEventsWithin(event, currentEvent)) {
                currentEvent.setStreamEvent(currentState, event);
                StateEvent newEvent = (StateEvent) filterProcessor.process(currentEvent);
                if (newEvent != null) {
                    processSuccessEvent(newEvent);
                } else {

                    currentEvent.setStreamEvent(currentState, null);
                }
            }
        }
    }

    protected void processSuccessEvent(StateEvent stateEvent) {
        if (log.isDebugEnabled()) {
            log.debug("sp state=" + state.getStateNumber() + " event=" + stateEvent);
        }
        setEventState(stateEvent);
        if (state.isLast()) {
            sendEvent(stateEvent);
        }
        passToNextStates(stateEvent);
    }

    protected Collection<StateEvent> getCollection() {
        Collection<StateEvent> collection;
        if (distributedProcessing) {
            if (within > -1) {
                collection = ((StateListGrid) currentEvents).getCollection("( timeStamp < " + (System.currentTimeMillis() + within) + ")");
            } else {
                collection = currentEvents.getCollection();
            }
        } else {
            collection = currentEvents.getCollection();
        }
        return collection;
    }

    protected boolean isEventsWithin(StreamEvent incomingEvent, StateEvent currentEvent) {
        if (log.isDebugEnabled()) {
            log.debug("Time difference for Sequence events " + (incomingEvent.getTimeStamp() - currentEvent.getFirstEventTimeStamp()));
        }
        if (within == -1 || currentEvent.getFirstEventTimeStamp() == 0) {
            return true;
        } else if ((incomingEvent.getTimeStamp() - currentEvent.getFirstEventTimeStamp()) <= within) {
            return true;
        } else {
            return false;
        }
    }


    public String getStreamId() {
        return state.getTransformedStream().getStreamId();
    }

    public void addToNextEvents(StateEvent stateEvent) {
//        System.out.println("add to next ss");
        try {
            nextEvents.put(stateEvent);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }

    public void moveNextEventsToCurrentEvents() {
        //todo need to check which is faster
        // 1
        currentEvents.clear();
        currentEvents.addAll(nextEvents.getAll());
        nextEvents.clear();

//        eventBank.clear();
//        eventBank.addAll(nextEvents);
//        nextEvents.clear();

//        // 2
//        eventBank = nextEvents;
//        nextEvents = new LinkedBlockingQueue<StateEvent>();
    }

    @Override
    public void setPersistenceStore(PersistenceStore persistenceStore) {
        this.persistenceStore = persistenceStore;
    }

    @Override
    public void save(PersistenceManagementEvent persistenceManagementEvent) {
        persistenceStore.save(persistenceManagementEvent, nodeId, new PersistenceObject(currentEvents.currentState(), nextEvents.currentState()));
    }

    @Override
    public void load(PersistenceManagementEvent persistenceManagementEvent) {
        PersistenceObject persistenceObject = persistenceStore.load(persistenceManagementEvent, nodeId);
        currentEvents.restoreState((Object[]) persistenceObject.getData()[0]);
        nextEvents.restoreState((Object[]) persistenceObject.getData()[1]);

    }

    public void setWithin(long within) {
        this.within = within;
    }

    public void updateToCurrentEvents(StateEvent updateContainingStateEvent, int updatingState) {
        ((StateListGrid) currentEvents).update(updateContainingStateEvent, updatingState);
    }

    public void updateToNextEvents(StateEvent updateContainingStateEvent, int updatingState) {
        ((StateListGrid) nextEvents).update(updateContainingStateEvent, updatingState);
    }

    public void removeFromCurrentEvents(StateEvent removingStateEvent) {
        ((StateListGrid) currentEvents).remove(removingStateEvent);
    }

    public void removeFromNextEvents(StateEvent removingStateEvent) {
        ((StateListGrid) nextEvents).remove(removingStateEvent);
    }

    @Override
    public String getElementId() {
        return nodeId;
    }

    @Override
    public void setElementId(String elementId) {
        this.nodeId = elementId;
    }


    protected void setEventState(StateEvent eventBundle) {
        eventBundle.setEventState(state.getStateNumber());
    }

    protected void sendEvent(AtomicEvent atomicEvent) {
        queryProjector.process(atomicEvent);
    }

    protected void passToNextStates(StateEvent eventBundle) {
        if (nextState != null) {
            if (log.isDebugEnabled()) {
                log.debug("->" + nextState.getStateNumber());
            }
            if (nextState instanceof OrSequenceState) {
                if (log.isDebugEnabled()) {
                    log.debug("->" + ((OrSequenceState) nextState).getPartnerState().getStateNumber());
                }
                ((OrSequenceState) nextState).getPartnerState().getSequenceInnerHandlerProcessor().addToNextEvents(eventBundle);
            }
            nextState.getSequenceInnerHandlerProcessor().addToNextEvents(eventBundle);
        }
    }

    public void setNext(QueryProjector queryProjector) {
        this.queryProjector = queryProjector;
    }

    public void reset() {
        currentEvents.clear();
        nextEvents.clear();
        reInit();
    }
}
