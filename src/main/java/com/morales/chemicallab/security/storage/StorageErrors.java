package com.morales.chemicallab.security.storage;
import java.sql.SQLException;
import java.util.*;
public final class StorageErrors {
    private StorageErrors(){}
    public static boolean quota(Throwable ex) {
        var seen=Collections.newSetFromMap(new IdentityHashMap<Throwable,Boolean>());
        for(Throwable t=ex;t!=null && seen.add(t);t=t.getCause()) {
            if(t instanceof SQLException sql) for(SQLException s=sql;s!=null;s=s.getNextException()) if("P7501".equals(s.getSQLState()))return true;
        }
        return false;
    }
    public static String message(){return "Se alcanzó la cuota de almacenamiento. Elimina recursos autorizados o reemplaza por uno de menor tamaño; tus datos y sesión se conservan.";}
}
