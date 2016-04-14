package com.umaplay.fluxxan4j.impl;

import com.sun.javafx.beans.annotations.NonNull;
import com.umaplay.fluxxan4j.Action;
import com.umaplay.fluxxan4j.DispatchResult;
import com.umaplay.fluxxan4j.Dispatcher;
import com.umaplay.fluxxan4j.Reducer;
import com.umaplay.fluxxan4j.StateListener;
import com.umaplay.fluxxan4j.WaitCallback;
import com.umaplay.fluxxan4j.util.CollectionUtils;
import com.umaplay.fluxxan4j.util.TextUtils;
import com.umaplay.fluxxan4j.util.ThreadUtils;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Created by user on 5/5/2015.
 */
public class DispatcherImpl<State> implements Dispatcher<State> {
    private static final String TAG = "DroidFlux:Dispatcher";
    private static final Logger Log= Logger.getLogger(TAG);

    protected State mState;
    protected final LinkedBlockingQueue<Action> mDispatchQueue = new LinkedBlockingQueue<Action>();
    protected final ConcurrentHashMap<String, Reducer<State>> mReducers = new ConcurrentHashMap<String, Reducer<State>>();
    protected AtomicBoolean mIsDispatching = new AtomicBoolean(false);
    protected String mCurrentActionType = null;
    protected Collection<String> mWaitingToDispatch;
    protected final List<StateListener<State>> mListeners;


    protected boolean isStarted;
    private Thread mDispatchThread;
    private long mThreadId;

    /**
     * Your initial state tree
     *
     * @param state
     */
    public DispatcherImpl(State state) {
        mState = state;
        mListeners = Collections.synchronizedList(new ArrayList<StateListener<State>>());
    }

    protected void _dispatch(@NonNull final Action action) {
        ThreadUtils.ensureNotOnMain();

        String[] names = mReducers.keySet().toArray(new String[mReducers.size()]);
        for (String name : names) {
            Reducer reducer = mReducers.get(name);
            reducer.reset();
        }

        mCurrentActionType = action.Type;
        mWaitingToDispatch = new HashSet<String>(mReducers.keySet());

        mIsDispatching.set(true);

        RuntimeException ex = null;
        try {
            Log.log(Level.INFO, String.format("[STARTED] dispatch of action [%s]", action.Type));
            doDispatchLoop(action);
            Log.log(Level.INFO, String.format("[COMPLETED] dispatch of action [%s]", action.Type));
        }
        catch (Exception e) {
            Log.log(Level.WARNING, String.format("[FAILED] dispatch of action [%s]", action.Type), e);
            ex = new RuntimeException(e);
        }
        finally {
            mCurrentActionType = null;
            mIsDispatching.set(false);
        }

        //we need to propagate the exception
        if(ex != null) throw ex;
    }

    protected synchronized void doDispatchLoop(Action action) throws Exception {
        ThreadUtils.ensureNotOnMain();

        Reducer<State> dispatch;
        Boolean canBeDispatchedTo;
        Boolean wasHandled = false;
        List<String> removeFromDispatchQueue = new ArrayList<String>();
        List<String> dispatchedThisLoop = new ArrayList<String>();
        State dispatchState = mState;

        for (String key : mWaitingToDispatch) {
            dispatch = mReducers.get(key);
            canBeDispatchedTo = (dispatch.getWaitingOnList().size() == 0) || (CollectionUtils.intersection(dispatch.getWaitingOnList(), new ArrayList<String>(mWaitingToDispatch)).size() == 0);

            if (canBeDispatchedTo) {
                if (dispatch.getWaitCallback() != null) {
                    WaitCallback fn = dispatch.getWaitCallback();
                    dispatch.reset();
                    dispatch.setResolved(true);
                    fn.call();
                    wasHandled = true;
                } else {
                    dispatch.setResolved(true);

                    DispatchResult<State> result = mReducers.get(key).reduce(dispatchState, action);
                    dispatchState = result.state;

                    if (result.handled) {
                        wasHandled = true;
                    }
                }

                dispatchedThisLoop.add(key);
                if (dispatch.isResolved()) {
                    removeFromDispatchQueue.add(key);
                }
            }
        }

        if (mWaitingToDispatch.size() > 0 && dispatchedThisLoop.size() == 0) {
            String reducersWithCircularWaits = CollectionUtils.implode(mWaitingToDispatch.iterator());
            throw new Exception("Indirect circular wait detected among: " + reducersWithCircularWaits);
        }

        for(int i = 0; i < removeFromDispatchQueue.size(); i++)
            mWaitingToDispatch.remove(removeFromDispatchQueue.get(i));

        if (mWaitingToDispatch.size() > 0) {
            this.doDispatchLoop(action);
        }

        if (!wasHandled) {
            Log.log(Level.INFO, String.format("An action of type [%s] was dispatched, but no reducer handled it", action.Type));
        }
        else if(hasStateChanged(dispatchState, mState)){
            State oldstate = mState;
            mState = dispatchState;//update state

            notifyListeners(dispatchState, oldstate);
        }
    }

    protected void notifyListeners(State newState, State oldState) {
        synchronized (mListeners) {
            Exception exception = null;
            for (StateListener<State> listener : mListeners) {
                try {
                    if(listener.hasStateChanged(newState, oldState)) listener.onStateChanged(newState);
                } catch (Exception e) {
                    Log.log(Level.WARNING, "Unexpected exception during notifyAll", e);
                    exception = e;//let's save this for after. It might just fuck things up terribly
                }
            }

            if(exception != null) throw new RuntimeException(exception);
        }
    }

    //------ public api

    /**
     * {@inheritDoc}
     * This must be called before any dispatch events else a runtime exception will be thrown
     */
    public void start() {
        if(isStarted) return;

        mDispatchThread = new Thread(new DispatchThread());
        mDispatchThread.start();
        isStarted = true;
    }

    /**
     * {@inheritDoc}
     * This should be called during cleanup to release any resources or when dispatching is no longer required
     */
    public void stop() {
        isStarted = false;
        mDispatchThread.interrupt();
        try {
            mDispatchThread.join(5000);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
        mDispatchThread = null;
        mThreadId = 0;
    }

    /**
     * {@inheritDoc}
     * Dispatching occurs on a dedicated background thread and are processed sequentially.
     *
     * @throws IllegalStateException if {@link DispatcherImpl#start} has not been called or it's called from within a dispatch cycle
     * @throws IllegalArgumentException if action type is empty. {@link TextUtils#isEmpty}
     */
    public void dispatch(@NonNull Action action) {
        if(!isStarted) {
            throw new IllegalStateException("Dispatcher not started!");
        }

        if(mThreadId == ThreadUtils.getId() && isDispatching()) {
            throw new IllegalStateException("Cannot call dispatch while dispatching!");
        }

        if (TextUtils.isEmpty(action.Type)) {
            throw new IllegalArgumentException("Can only dispatch actions with a valid 'Type' property");
        }

        mDispatchQueue.offer(action);
    }

    @Override
    public <T extends Reducer<State>> T getReducer(Class<T> reducerClass) {
        return (T) mReducers.get(reducerClass.getName());
    }

    @Override
    public Collection<Reducer<State>> getReducers() {
        return mReducers.values();
    }

    /**
     * {@inheritDoc}
     * If a reducer of the same type is already registered, it will be replaced with the new one
     */
    @Override
    public Reducer<State> registerReducer(@NonNull Reducer<State> reducer) {
        reducer.setDispatcher(this);

        return mReducers.put(reducer.getClass().getName(), reducer);
    }

    /**
     * {@inheritDoc}
     * Uses {@link #registerReducer} internally
     */
    @Override
    public Collection<Reducer<State>> registerReducers(@NonNull List<Reducer<State>> reducers) {
        for (Reducer<State> reducer : reducers) {
            registerReducer(reducer);
        }

        return mReducers.values();
    }

    @Override
    public <T extends Reducer<State>> T unregisterReducer(Class<T> reducer) {
        return (T) mReducers.remove(reducer.getName());
    }

    @Override
    public boolean addListener(StateListener<State> StateListener) {
        removeListener(StateListener);
        return mListeners.add(StateListener);
    }

    @Override
    public boolean removeListener(StateListener<State> StateListener) {
        return mListeners.remove(StateListener);
    }

    /**
     * This will throw an IllegalStateException If a dispatch is not currently running or the reducer is already waiting (nested call possibly)
     * or a circular wait is detected.
     * It will also throw an IllegalArgumentException If reducer is in the Set of reducers to wait on or a specified reducer has not been registered.
     */
    @Override
    public void waitFor(Class waitingReducer, Set<Class> reducerNames, WaitCallback callback) {
        ThreadUtils.ensureNotOnMain();

        String waitingReducerName = waitingReducer.getName();

        if (!isDispatching()) {
            throw new IllegalStateException("Cannot wait unless an action is being dispatched");
        }

        if (reducerNames.contains(waitingReducer)) {
            throw new IllegalArgumentException("A reducer cannot wait on itself");
        }

        Reducer dispatch = mReducers.get(waitingReducerName);

        if (dispatch.getWaitingOnList().size() > 0) {
            throw new IllegalStateException(waitingReducerName + " is already waiting on reducers");
        }

        for (Class reducerName1 : reducerNames) {
            String reducerName = reducerName1.getName();

            if (!mReducers.containsKey(reducerName)) {
                throw new IllegalArgumentException("Cannot wait for non-existent reducer " + reducerName);
            }

            Reducer reducerDispatch = mReducers.get(reducerName);
            if (reducerDispatch.getWaitingOnList().contains(waitingReducerName)) {
                throw new IllegalStateException("Circular wait detected between " + waitingReducerName + " and " + reducerName);
            }
        }

        dispatch.reset();

        dispatch.setWaitCallback(callback);
        dispatch.addToWaitingOnList(reducerNames);
    }

    @Override
    public boolean isDispatching() {
        return mIsDispatching.get();
    }

    public State getState() {
        return mState;
    }


    /**
     * {@inheritDoc}
     * By default, it always returns true
     */
    @Override
    public boolean hasStateChanged(State newState, State oldState) {
        return newState != oldState;
    }

    //------ inner classes

    private final class DispatchThread implements Runnable {
        @Override
        public void run() {
            mThreadId = ThreadUtils.getId();
            boolean run = true;
            Action action;

            while (run) {
                if(mDispatchThread.isInterrupted()) return;

                try {
                    action = mDispatchQueue.take();
                    if(action != null) {
                        _dispatch(action);
                    }
                } catch (InterruptedException e) {
                    run = false;
                }
            }
        }
    }
}
