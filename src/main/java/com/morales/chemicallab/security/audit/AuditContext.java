package com.morales.chemicallab.security.audit;

import java.util.UUID;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** IDs originate on the server. No client header is an audit identity. */
public final class AuditContext implements AutoCloseable {
    private static final ThreadLocal<UUID> CURRENT = new ThreadLocal<>();
    private static final Object TRANSACTION_KEY = new Object();
    private final UUID previous;
    private static final class State {
        final UUID correlation;
        final java.util.Set<Long> revocations=new java.util.HashSet<>();
        State(UUID correlation){this.correlation=correlation;}
    }
    private AuditContext(UUID id) { previous=CURRENT.get(); CURRENT.set(id); }
    public static AuditContext open(UUID id) { return new AuditContext(id); }
    public static UUID correlation() {
        if (CURRENT.get()!=null) return CURRENT.get();
        if (!TransactionSynchronizationManager.isSynchronizationActive()) return UUID.randomUUID();
        return transactionState().correlation;
    }
    public static boolean claimRevocation(Long targetId){
        return transactionState().revocations.add(targetId);
    }
    private static State transactionState(){
        State state=(State)TransactionSynchronizationManager.getResource(TRANSACTION_KEY);
        if(state==null) {
            state=new State(CURRENT.get()==null?UUID.randomUUID():CURRENT.get());
            TransactionSynchronizationManager.bindResource(TRANSACTION_KEY,state);
            final State captured=state;
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization(){
                @Override public void suspend(){ TransactionSynchronizationManager.unbindResourceIfPossible(TRANSACTION_KEY); }
                @Override public void resume(){ TransactionSynchronizationManager.bindResource(TRANSACTION_KEY,captured); }
                @Override public void afterCompletion(int status){ TransactionSynchronizationManager.unbindResourceIfPossible(TRANSACTION_KEY); }
            });
        }
        return state;
    }
    @Override public void close(){ if(previous==null) CURRENT.remove(); else CURRENT.set(previous); }
}
