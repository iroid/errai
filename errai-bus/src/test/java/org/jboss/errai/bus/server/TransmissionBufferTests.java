/*
 * Copyright 2011 JBoss, by Red Hat, Inc
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *  
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.jboss.errai.bus.server;

import junit.framework.TestCase;
import org.jboss.errai.bus.client.tests.support.SType;
import org.jboss.errai.bus.server.io.buffers.BufferColor;
import org.jboss.errai.bus.server.io.buffers.TransmissionBuffer;
import org.jboss.errai.bus.tests.JSONTests;
import org.jboss.errai.marshalling.server.JSONEncoder;

import java.io.*;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;

/**
 * @author Mike Brock
 */
public class TransmissionBufferTests extends TestCase {


  public void testBufferWriteAndRead() {
    TransmissionBuffer buffer = TransmissionBuffer.create();

    String s = "This is a test";

    BufferColor colorA = BufferColor.getNewColor();


    try {
      ByteArrayInputStream bInputStream = new ByteArrayInputStream(s.getBytes());
      buffer.write(s.length(), bInputStream, colorA);

      ByteArrayOutputStream bOutputStream = new ByteArrayOutputStream();
      buffer.read(bOutputStream, colorA);

      assertEquals(s, new String(bOutputStream.toByteArray()));
    }
    catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  public void testBufferCycle() throws IOException {
    TransmissionBuffer buffer = TransmissionBuffer.create(10, 5);

    BufferColor color = BufferColor.getNewColor();

    String s = "12345789012345";

    long start = System.currentTimeMillis();
    for (int i = 0; i < 1000000; i++) {
      ByteArrayInputStream bInputStream = new ByteArrayInputStream(s.getBytes());
      ByteArrayOutputStream bOutputStream = new ByteArrayOutputStream();

      buffer.write(s.length(), bInputStream, color);
      buffer.read(bOutputStream, color);

      assertEquals(s, new String(bOutputStream.toByteArray()));
    }
    System.out.println(System.currentTimeMillis() - start);
  }


  public void testColorInterleaving() throws IOException {
    TransmissionBuffer buffer = TransmissionBuffer.create(10, 20);

    BufferColor colorA = BufferColor.getNewColor();
    BufferColor colorB = BufferColor.getNewColor();
    BufferColor colorC = BufferColor.getNewColor();


    String stringA = "12345678";
    String stringB = "ABCDEFGH";
    String stringC = "IJKLMNOP";


    long start = System.currentTimeMillis();
    for (int i = 0; i < 1000000; i++) {
      ByteArrayInputStream bInputStream = new ByteArrayInputStream(stringA.getBytes());
      buffer.write(stringA.length(), bInputStream, colorA);

      bInputStream = new ByteArrayInputStream(stringB.getBytes());
      buffer.write(stringB.length(), bInputStream, colorB);

      bInputStream = new ByteArrayInputStream(stringC.getBytes());
      buffer.write(stringC.length(), bInputStream, colorC);


      ByteArrayOutputStream bOutputStream = new ByteArrayOutputStream();
      buffer.read(bOutputStream, colorA);
      assertEquals(stringA, new String(bOutputStream.toByteArray()));

      bOutputStream = new ByteArrayOutputStream();
      buffer.read(bOutputStream, colorB);
      assertEquals(stringB, new String(bOutputStream.toByteArray()));

      bOutputStream = new ByteArrayOutputStream();
      buffer.read(bOutputStream, colorC);
      assertEquals(stringC, new String(bOutputStream.toByteArray()));

    }
    System.out.println(System.currentTimeMillis() - start);
  }

  final static int COLOR_COUNT = 1;

  public void testAudited() throws Exception {

    final List<BufferColor> colors = new ArrayList<BufferColor>();

    final TransmissionBuffer buffer = TransmissionBuffer.create();

    for (int i = 0; i < COLOR_COUNT; i++) {
      colors.add(BufferColor.getNewColor());
    }
    final Random random = new Random(2234);

    final String[] writeString = {"<JIMMY>", "<CRAB>", "<KITTY>", "<DOG>", "<JONATHAN>"};


    final Map<Short, List<String>> writeLog = new HashMap<Short, List<String>>();

    final int createCount = 500;


    final AtomicInteger totalWrites = new AtomicInteger();

    List<String> results = Collections.synchronizedList(new ArrayList<String>());

    for (int i = 0; i < createCount; i++) {
      final BufferColor toContend = colors.get(random.nextInt(COLOR_COUNT));
      assertNotNull(toContend);
      new Runnable() {
        @Override
        public void run() {
          try {
            String toWrite = writeString[random.nextInt(writeString.length)];
            ByteArrayInputStream byteArrayInputStream = new ByteArrayInputStream(toWrite.getBytes());
            buffer.write(toWrite.getBytes().length, byteArrayInputStream, toContend);
            totalWrites.incrementAndGet();

            List<String> stack = writeLog.get(toContend.getColor());
            if (stack == null) {
              writeLog.put(toContend.getColor(), stack = new ArrayList<String>());
            }

            stack.add(toWrite);

            System.out.println("Wrote color " + toContend.getColor() + ": " + toWrite + ". Total writes is now " + totalWrites);
          }
          catch (IOException e) {
            e.printStackTrace();
          }
        }
      }.run();
    }

    assertEquals(createCount, totalWrites.intValue());

    AtomicInteger resultSequenceNumber = new AtomicInteger();

    for (int i = 0; i < COLOR_COUNT; i++) {
      resultSequenceNumber.incrementAndGet();

      final ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
      byteArrayOutputStream.reset();
      assertEquals(0, byteArrayOutputStream.size());
      buffer.read(byteArrayOutputStream, colors.get(i));
      assertTrue("Expected >0 bytes; got " + byteArrayOutputStream.size(), byteArrayOutputStream.size() > 0);

      String val = new String(byteArrayOutputStream.toByteArray());
      results.add(val);

      List<String> buildResultList = new ArrayList<String>();

      int st = 0;
      for (int c = 0; c < val.length(); c++) {
        switch (val.charAt(c)) {
          case '>':
            c++;
            buildResultList.add(val.substring(st, st = c));
        }
      }


      List<String> resultList = new ArrayList<String>(buildResultList);
      List<String> log = new ArrayList<String>(writeLog.get(colors.get(i).getColor()));

      while (!log.isEmpty() && !resultList.isEmpty()) {
        String nm = log.remove(0);
        String test = resultList.remove(0);
        if (!nm.equals(test)) {
          System.out.println("[" + resultSequenceNumber + "] expected : " + nm + " -- but found: " + test
                  + " (color: " + colors.get(i).getColor() + ")");

          System.out.println("  --> log: " + writeLog.get(colors.get(i).getColor()) + " vs result: " + buildResultList);
        }
      }


      if (!log.isEmpty())
        System.out.println("[" + resultSequenceNumber + "] results have missing items: " + log
                + " (color: " + colors.get(i).getColor() + ")");

      if (!resultList.isEmpty())
        System.out.println("[" + resultSequenceNumber + "] results contain items not logged: " + resultList
                + " (color: " + colors.get(i).getColor() + ")");
    }

    assertEquals(COLOR_COUNT, results.size());

    int count = 0;
    for (String res : results) {
      for (int i = 0; i < res.length(); i++) {
        if (res.charAt(i) == '<') count++;
      }

      System.out.println();
      System.out.print(res);
    }


    buffer.dumpSegments(new PrintWriter(System.out));

    assertEquals(createCount, count);
  }

  public void testLargeOversizedSegments() {
    final TransmissionBuffer buffer = TransmissionBuffer.create();

    final BufferColor colorA = BufferColor.getNewColor();

    SType type = SType.create(new JSONTests.JavaRandomProvider());

    Map<String, Object> vars = new HashMap<String, Object>();
    vars.put("SType", type);

    String s = JSONEncoder.encode(vars);

    ByteArrayInputStream bInputStream = new ByteArrayInputStream(s.getBytes());
    ByteArrayOutputStream bOutputStream = new ByteArrayOutputStream();

    try {
      for (int i = 0; i < 10000; i++) {
        bInputStream.reset();
        buffer.write(s.length(), bInputStream, colorA);

        bOutputStream.reset();
        buffer.read(bOutputStream, colorA);
        assertEquals(s, new String(bOutputStream.toByteArray()));
      }
    }
    catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  final static int SEGMENT_COUNT = 10;

  public void testMultithreadedBufferUse() throws Exception {
    File logFile = new File("multithread_test.log");
    File rawBufferFile = new File("raw_buffer.log");
    if (!logFile.exists()) logFile.createNewFile();
    if (!rawBufferFile.exists()) rawBufferFile.createNewFile();


    final TransmissionBuffer buffer = TransmissionBuffer.createDirect(32, 32000);

    OutputStream fileLog = new BufferedOutputStream(new FileOutputStream(logFile, false));
    OutputStream rawBuffer = new BufferedOutputStream(new FileOutputStream(rawBufferFile, false));

    final PrintWriter logWriter = new PrintWriter(fileLog);


    logWriter.println("START SESSION: " + new Date().toString());
    try {


      final List<BufferColor> segs = new ArrayList<BufferColor>();
      for (int i = 0; i < SEGMENT_COUNT; i++) {
        segs.add(BufferColor.getNewColor());
      }


      final Collection<String> writeAuditLog = new ConcurrentLinkedQueue<String>();
      final Collection<String> readAuditLog = new ConcurrentLinkedQueue<String>();

      final int createCount = 10000;
      final String[] writeString = new String[createCount];

      for (int i = 0; i < createCount; i++) {
        writeString[i] = "<:::" + i + ":::>";
      }

      for (int outerCount = 0; outerCount < 10; outerCount++) {
        ScheduledThreadPoolExecutor exec = new ScheduledThreadPoolExecutor(10);

        logWriter.print("SESSION NUMBER " + outerCount);

        System.out.println("Running multi-threaded stress test (" + (outerCount + 1) + ") ...");

        writeAuditLog.clear();
        readAuditLog.clear();

        final AtomicInteger totalWrites = new AtomicInteger();
        final AtomicInteger totalReads = new AtomicInteger();

        final CountDownLatch latch = new CountDownLatch(createCount);

        class TestReader {
          volatile boolean running = true;

          public void read(BufferColor color, boolean wait) throws Exception {
             ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
            if (wait) {
              buffer.readWait(TimeUnit.SECONDS, 1, byteArrayOutputStream, color);
            }
            else {
              buffer.read(byteArrayOutputStream, color);
            }

            String val = new String(byteArrayOutputStream.toByteArray()).trim();
            List<String> buildResultList = new ArrayList<String>();

            logWriter.println(val);

            int st = 0;
            for (int c = 0; c < val.length(); c++) {
              switch (val.charAt(c)) {
                case '>': {
                  buildResultList.add(val.substring(st, st = (c + 1)));
                }
              }
            }

            if (st < val.length()) {
              fail("malformed data: {{" + val + "}}");
            }

            if (val.length() > 0 && val.charAt(val.length() - 1) != '>') {
              fail("malformed data: " + val);
            }

            boolean match;
            for (String s : buildResultList) {
              match = false;
              for (String testString : writeString) {
                if (s.equals(testString)) {
                  totalReads.incrementAndGet();
                  match = true;
                }
              }
              assertTrue("unrecognized test string: {{" + s + "}}", match);
            }

            readAuditLog.addAll(buildResultList);

           // totalReads.addAndGet(buildResultList.size());
          }
        }

        final TestReader testReader = new TestReader();

        final Thread[] readers = new Thread[SEGMENT_COUNT];
        for (int i = 0; i < SEGMENT_COUNT; i++) {
          final int item = i;

          readers[i] = new Thread() {
            final BufferColor color = segs.get(item);

            @Override
            public void run() {
              try {
                while (testReader.running) {
                  testReader.read(color, true);
                }
              }
              catch (Throwable t) {
                t.printStackTrace();
              }
            }
          };

          readers[i].start();
        }

        for (int i = 0; i < createCount; i++) {
          final int item = i;

          exec.execute(new Runnable() {
            @Override
            public void run() {
              try {
                String toWrite = writeString[item];
                writeAuditLog.add(toWrite);

                ByteArrayInputStream byteArrayInputStream = new ByteArrayInputStream(toWrite.getBytes());
                buffer.write(toWrite.length(), byteArrayInputStream, segs.get(item % SEGMENT_COUNT));

                totalWrites.incrementAndGet();
                latch.countDown();
              }
              catch (IOException e) {
                e.printStackTrace();
              }
            }
          });
        }

        /**
         * Wait a maximum of 20 seconds.
         */
        latch.await(30, TimeUnit.SECONDS);

        LockSupport.parkNanos(TimeUnit.SECONDS.toNanos(2));

        testReader.running = false;
        for (Thread t : readers) {
          t.join();
        }

        exec.shutdownNow();

        if (totalWrites.intValue() != totalReads.intValue()) {
          /**
           * Double check that there isn't anything un-read.
           */

          LockSupport.parkNanos(100000);

          for (int i = 0; i < SEGMENT_COUNT; i++) {
            try {
              testReader.read(segs.get(i), false);
            }
            catch (Exception e) {
              e.printStackTrace();
            }
          }

          if (totalWrites.intValue() != totalReads.intValue()) {
            //      new ReadWriteOrderAnalysis().analyze();

            System.out.println("-----");

            System.out.println("(" + (outerCount + 1) + ") different number of reads and writes (writes=" + totalWrites + ";reads=" + totalReads + ")");
          }
        }


//        assertEquals(totalWrites.intValue(), totalReads.intValue());

        System.out.println("Read / Write Symmetry Analysis ... ");
        for (String s : writeAuditLog) {
          if (!readAuditLog.contains(s)) {
            Collection<String> leftDiff = new ArrayList<String>(writeAuditLog);
            leftDiff.removeAll(readAuditLog);

            Collection<String> rightDiff = new ArrayList<String>(readAuditLog);
            rightDiff.removeAll(writeAuditLog);

            Set<String> uniqueReads = new HashSet<String>(readAuditLog);

            List<String> duplicates = new ArrayList<String>(readAuditLog);
            if (uniqueReads.size() < readAuditLog.size()) {
              for (String str : uniqueReads) {
                duplicates.remove(duplicates.indexOf(str));
              }
            }

            System.out.println("duplicates: " + duplicates);

            //    new ReadWriteOrderAnalysis().analyze();

            fail(s + " was written, but never read (leftDiff=" + leftDiff + ";rightDiff=" + rightDiff
                    + ";duplicatesInReadLog=" + duplicates + ")");
          }
        }

        System.out.println("Done.\n");
      }
    }
    finally {
      buffer.dumpSegments(logWriter);
      buffer.rawDump(rawBuffer);

      logWriter.flush();

      fileLog.flush();
      fileLog.close();

      rawBuffer.flush();
      rawBuffer.close();
    }
  }

  public void testGloballyVisibleColors() throws IOException {
    BufferColor colorA = BufferColor.getNewColor();
    BufferColor colorB = BufferColor.getNewColor();

    BufferColor globalColor = BufferColor.getAllBuffersColor();

    TransmissionBuffer buffer = TransmissionBuffer.create(5, 2500);

    String stringA = "12345678";
    String stringB = "ABCDEFGH";
    String stringC = "IJKLMNOP";


    long start = System.currentTimeMillis();
    for (int i = 0; i < 1000000; i++) {
      ByteArrayInputStream bInputStream = new ByteArrayInputStream(stringA.getBytes());
      buffer.write(stringA.length(), bInputStream, colorA);

      bInputStream = new ByteArrayInputStream(stringB.getBytes());
      buffer.write(stringB.length(), bInputStream, colorB);

      bInputStream = new ByteArrayInputStream(stringC.getBytes());
      buffer.write(stringC.length(), bInputStream, globalColor);

      ByteArrayOutputStream bOutputStream = new ByteArrayOutputStream();
      buffer.read(bOutputStream, colorA);
      assertEquals(stringA + stringC, new String(bOutputStream.toByteArray()));

      bOutputStream = new ByteArrayOutputStream();
      buffer.read(bOutputStream, colorB);
      assertEquals(stringB + stringC, new String(bOutputStream.toByteArray()));

    }
    System.out.println(System.currentTimeMillis() - start);
  }

}