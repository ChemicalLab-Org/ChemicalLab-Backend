import com.sun.tools.attach.VirtualMachine;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import javax.management.ObjectName;
import javax.management.remote.JMXConnectorFactory;
import javax.management.remote.JMXServiceURL;

class SampleLocalPool {
    public static void main(String[] args) throws Exception {
        var vm = VirtualMachine.attach(args[0]);
        String address;
        try {
            address = vm.startLocalManagementAgent();
        } finally {
            vm.detach();
        }
        try (var connector = JMXConnectorFactory.connect(new JMXServiceURL(address))) {
            var server = connector.getMBeanServerConnection();
            var pool = new ObjectName("com.zaxxer.hikari:type=Pool (CapacityPool)");
            ThreadMXBean threads = ManagementFactory.newPlatformMXBeanProxy(server,
                ManagementFactory.THREAD_MXBEAN_NAME, ThreadMXBean.class);
            var os = new ObjectName(ManagementFactory.OPERATING_SYSTEM_MXBEAN_NAME);
            var memory = ManagementFactory.newPlatformMXBeanProxy(server, ManagementFactory.MEMORY_MXBEAN_NAME,
                java.lang.management.MemoryMXBean.class);
            System.out.println("epochMs,active,idle,total,waiting,threads,processCpuNs,heapUsedBytes");
            while (true) {
                int waiting = (int) server.getAttribute(pool, "ThreadsAwaitingConnection");
                System.out.printf("%d,%s,%s,%s,%d,%d,%s,%d%n", System.currentTimeMillis(),
                    server.getAttribute(pool, "ActiveConnections"), server.getAttribute(pool, "IdleConnections"),
                    server.getAttribute(pool, "TotalConnections"), waiting, threads.getThreadCount(),
                    server.getAttribute(os, "ProcessCpuTime"), memory.getHeapMemoryUsage().getUsed());
                if (waiting > 0) {
                    for (var thread : threads.getThreadInfo(threads.getAllThreadIds(), 45)) {
                        if (thread == null) continue;
                        var stack = java.util.Arrays.asList(thread.getStackTrace());
                        if (stack.stream().anyMatch(frame -> frame.getClassName().equals("com.zaxxer.hikari.pool.HikariPool")
                            && frame.getMethodName().equals("getConnection"))) {
                            System.err.printf("%d %s %s%n", System.currentTimeMillis(), thread.getThreadName(), thread.getThreadState());
                            stack.forEach(frame -> System.err.println("    " + frame));
                        }
                    }
                }
                Thread.sleep(100);
            }
        }
    }
}
