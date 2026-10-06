package io.github.lwjlw.bilibtr.recon;

import android.os.Process;
import android.util.Log;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * 统一落点：logcat + 文件。
 *
 * 为什么一定要落盘：logcat 环形缓冲会被刷掉，而侦察要的是"完整画像"；
 * 而且 4G 信号下真机日志量不小。文件走宿主（tv.danmaku.bili）的
 * external files dir → 免 root 直接 `adb pull`。
 *
 * 写文件全部在独立线程做，hook 回调里只做入队，绝不阻塞播放线程。
 */
public final class Sink {

    private static final BlockingQueue<String> QUEUE = new LinkedBlockingQueue<>(40000);
    private static final SimpleDateFormat FMT = new SimpleDateFormat("HH:mm:ss.SSS", Locale.US);
    /** 单个日志文件上限。**固定 3 个文件滚动**，所以总占用上限 = 3 × 这个值。 */
    private static final long MAX_BYTES = 8L * 1024 * 1024;
    /** 滚动日志的文件名（固定名字，不按 PID —— 见 {@link #rotate()} 的说明）。 */
    private static final String[] NAMES = {"recon.log", "recon-1.log", "recon-2.log"};

    private static volatile boolean started = false;
    private static volatile File logFile;

    private Sink() {
    }

    public static void write(String line) {
        String stamped = FMT.format(new Date()) + " " + line;
        logcat(stamped);
        ensureStarted();
        if (!QUEUE.offer(stamped)) {
            // 队列满说明写得比落盘快：丢弃，但留个痕迹
            Log.w(Recon.TAG, "sink queue full, drop: " + Recon.clip(line, 120));
        }
    }

    private static synchronized void ensureStarted() {
        if (started) return;
        started = true;
        Thread t = new Thread(Sink::loop, "btr-recon-sink");
        t.setDaemon(true);
        t.start();
    }

    /** logcat 单条有长度上限，超长要切块，否则会被静默截断。 */
    private static void logcat(String s) {
        if (s.length() <= 3500) {
            Log.i(Recon.TAG, s);
            return;
        }
        int i = 0;
        while (i < s.length()) {
            int end = Math.min(s.length(), i + 3500);
            Log.i(Recon.TAG, s.substring(i, end));
            i = end;
        }
    }

    private static void loop() {
        File dir = AppHolder.reconDir();
        if (dir == null) {
            Log.w(Recon.TAG, "no writable dir for recon log; logcat only");
            return;
        }
        cleanupLegacy(dir);
        logFile = new File(dir, NAMES[0]);
        BufferedWriter w = null;
        try {
            w = open(logFile);
            long lastFlush = System.currentTimeMillis();
            while (true) {
                String line = QUEUE.poll(1, java.util.concurrent.TimeUnit.SECONDS);
                if (line != null) w.write(line + "\n");
                if (line != null && System.currentTimeMillis() - lastFlush > 500) {
                    w.flush();
                    lastFlush = System.currentTimeMillis();
                } else if (line == null) {
                    w.flush();
                    lastFlush = System.currentTimeMillis();
                }
                if (logFile.length() > MAX_BYTES) {
                    w.flush();
                    w.close();
                    rotate();
                    w = open(logFile);
                }
            }
        } catch (Throwable t) {
            Log.w(Recon.TAG, "sink thread died", t);
        }
    }

    /**
     * 滚动：`recon-2.log` 删掉 → `recon-1.log` 改名过去 → `recon.log` 改名过去 → 重新开一个。
     *
     * ⚠️ **修掉的两个坑**（都很严重）：
     * 1. 文件名原来是 `recon-<pid>.log`：**PID 一变就换一个新文件**，旧的永不删除，
     *    每次重启 B站 都会多攒一对文件；
     * 2. 滚动目标原来**永远写成同一个 `-2.log` 且用 append 打开** →
     *    超过 8 MB 之后会一直往同一个文件追加，**等于没有上限**。
     *
     * 现在固定三个文件名滚动，总占用上限 = 3 × 8 MB = 24 MB。
     */
    private static void rotate() {
        try {
            File dir = logFile.getParentFile();
            if (dir == null) return;
            new File(dir, NAMES[2]).delete();
            new File(dir, NAMES[1]).renameTo(new File(dir, NAMES[2]));
            new File(dir, NAMES[0]).renameTo(new File(dir, NAMES[1]));
            logFile = new File(dir, NAMES[0]);
        } catch (Throwable ignored) {
        }
    }

    /** 把历史版本留下的 `recon-<pid>*.log` 清掉，否则它们会一直躺在设备里。 */
    private static void cleanupLegacy(File dir) {
        try {
            File[] fs = dir.listFiles();
            if (fs == null) return;
            for (File f : fs) {
                String n = f.getName();
                if (!n.startsWith("recon-") || !n.endsWith(".log")) continue;
                if (n.equals(NAMES[1]) || n.equals(NAMES[2])) continue;   // 保留滚动文件
                // 只删形如 recon-<数字>*.log 的旧文件
                if (n.matches("recon-\\d+.*\\.log")) f.delete();
            }
        } catch (Throwable ignored) {
        }
    }

    /** 追加打开（滚动后的文件也要保留内容）。 */
    private static BufferedWriter open(File f) throws Exception {
        boolean fresh = !f.exists() || f.length() == 0;
        BufferedWriter w = new BufferedWriter(
                new OutputStreamWriter(new FileOutputStream(f, true), "UTF-8"), 8192);
        if (fresh) header(w);
        return w;
    }

    private static void header(BufferedWriter w) {
        try {
            w.write("\n===== BTR recon " + Recon.VERSION
                    + "  pid=" + Process.myPid()
                    + "  proc=" + AppHolder.processName()
                    + "  api=" + android.os.Build.VERSION.SDK_INT
                    + "  host=" + AppHolder.hostVersion()
                    + "  at=" + new Date() + " =====\n");
            w.flush();
        } catch (Throwable ignored) {
        }
    }

    public static File currentFile() {
        return logFile;
    }
}
