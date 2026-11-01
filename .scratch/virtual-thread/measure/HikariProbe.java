import com.sun.tools.attach.VirtualMachine;

import javax.management.MBeanServerConnection;
import javax.management.ObjectName;
import javax.management.remote.JMXConnector;
import javax.management.remote.JMXConnectorFactory;
import javax.management.remote.JMXServiceURL;
import java.io.File;
import java.io.PrintWriter;
import java.util.Set;

/**
 * 采样目标 JVM 的 Hikari 池状态与线程账（票 04 的「对照连接池口径」）。
 * 需要目标 JVM 以 spring.datasource.hikari.register-mbeans=true 启动。
 *
 * 用法: java HikariProbe <pid> <seconds> <out.csv>
 */
public class HikariProbe {

    public static void main(String[] args) throws Exception {
        String pid = args[0];
        int seconds = Integer.parseInt(args[1]);
        String out = args[2];

        VirtualMachine vm = VirtualMachine.attach(pid);
        String addr = vm.getAgentProperties()
                .getProperty("com.sun.management.jmxremote.localConnectorAddress");
        if (addr == null) {
            // JDK 21 起 management-agent.jar 已不再随 JDK 分发，改用 startLocalManagementAgent()
            addr = vm.startLocalManagementAgent();
        }
        if (addr == null) {
            throw new IllegalStateException("no local JMX connector address for pid " + pid);
        }
        JMXConnector conn = JMXConnectorFactory.connect(new JMXServiceURL(addr));
        // 采样过程中的任何异常都不该把 JMX 连接与 attach 泄漏给调用方（长驻用法下会攒下来）
        try {
            MBeanServerConnection mbsc = conn.getMBeanServerConnection();

            ObjectName poolPattern = new ObjectName("com.zaxxer.hikari:*");
            ObjectName threading = new ObjectName("java.lang:type=Threading");
            ObjectName os = new ObjectName("java.lang:type=OperatingSystem");

            try (PrintWriter w = new PrintWriter(out, "UTF-8")) {
                w.println("t_ms,pool,active,idle,total,awaiting,thread_count,peak_threads,cpu_load");
                long t0 = System.currentTimeMillis();
                long end = t0 + seconds * 1000L;
                while (System.currentTimeMillis() < end) {
                    long t = System.currentTimeMillis() - t0;
                    Set<ObjectName> pools = mbsc.queryNames(poolPattern, null);
                    int threads = (Integer) mbsc.getAttribute(threading, "ThreadCount");
                    int peak = (Integer) mbsc.getAttribute(threading, "PeakThreadCount");
                    double cpu = (Double) mbsc.getAttribute(os, "ProcessCpuLoad");
                    for (ObjectName p : pools) {
                        String type = p.getKeyProperty("type");
                        // 只取运行态池 MBean；PoolConfig 没有这些属性
                        if (type == null || !type.startsWith("Pool ")) {
                            continue;
                        }
                        w.printf("%d,%s,%s,%s,%s,%s,%d,%d,%.4f%n", t, type.replace(',', ' '),
                                String.valueOf(mbsc.getAttribute(p, "ActiveConnections")),
                                String.valueOf(mbsc.getAttribute(p, "IdleConnections")),
                                String.valueOf(mbsc.getAttribute(p, "TotalConnections")),
                                String.valueOf(mbsc.getAttribute(p, "ThreadsAwaitingConnection")),
                                threads, peak, cpu);
                    }
                    w.flush();
                    Thread.sleep(200);
                }
            }
            System.out.println("probe done -> " + out);
        } finally {
            conn.close();
            vm.detach();
        }
    }
}
