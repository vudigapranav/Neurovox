import analysis.VoiceReport;
import audio.VoiceSource;
import audio.VoiceSource.*;

import java.io.*;
import java.util.*;

// NEUROVOX - voice-based Parkinson's screening (console version).
// It does not diagnose: it only flags voices that should be checked by a doctor.
public class Main {
    static final String HISTORY = "history.csv";
    static List<VoiceReport> session = new ArrayList<>();

    public static void main(String[] args) {
        Scanner in = new Scanner(System.in);
        System.out.println("===== NEUROVOX : Voice-based Parkinson's Screening =====");
        System.out.print("Patient name: ");
        String name = in.nextLine().trim();
        if (name.isEmpty()) name = "Patient";

        int choice;
        do {
            System.out.println("\n1. Record my voice (5 s 'aah')\n2. Analyse a WAV file\n3. Demo - steady voice"
                    + "\n4. Demo - shaky voice\n5. Compare this session\n6. View saved history\n0. Exit");
            System.out.print("Choice: ");
            choice = in.hasNextInt() ? in.nextInt() : -1;
            in.nextLine();

            VoiceSource voice = null;            // superclass reference
            switch (choice) {
                case 1: voice = new Microphone(); break;
                case 2:
                    System.out.print("WAV file path: ");
                    voice = new WavFile(in.nextLine().trim());
                    break;
                case 3: voice = new Demo(); break;
                case 4: voice = new Demo(true); break;
                case 5: compare(); break;
                case 6: showHistory(); break;
                case 0: System.out.println("Goodbye."); break;
                default: System.out.println("Invalid choice.");
            }
            if (voice != null) screen(name, voice);
        } while (choice != 0);
        in.close();
    }

    static void screen(String name, VoiceSource voice) {
        try {
            if (voice instanceof Microphone) System.out.println("Say 'aaaah' steadily now...");
            voice.load();                        // dynamic method dispatch

            VoiceReport a = new VoiceReport(voice);
            Thread worker = new Thread(a, "analyzer");
            worker.start();
            System.out.print("Analysing");
            while (!a.isDone()) {
                System.out.print(".");
                Thread.sleep(50);
            }
            worker.join();

            System.out.println("\n" + a.report());
            session.add(a);
            saveHistory(name, a);
        } catch (VoiceException e) {
            System.out.println("\nCheck failed: " + e.getMessage());
        } catch (InterruptedException | IOException e) {
            System.out.println("\nError: " + e.getMessage());
        } finally {
            System.out.println("--------------------------------------------------");
        }
    }

    static void compare() {
        if (session.isEmpty()) {
            System.out.println("Nothing analysed yet.");
            return;
        }
        Collections.sort(session);               // uses compareTo (lowest jitter first)
        System.out.println("This session, steadiest voice first:");
        Iterator<VoiceReport> it = session.iterator();
        while (it.hasNext()) System.out.println("  " + it.next());
        long flagged = session.stream().filter(v -> v.needsFurtherCheck()).count();   // lambda
        System.out.println("Flagged for further check: " + flagged + " of " + session.size());
    }

    static void saveHistory(String name, VoiceReport a) throws IOException {
        try (BufferedWriter w = new BufferedWriter(new FileWriter(HISTORY, true))) {
            w.write(name + "," + new Date() + "," + a.toCsv());
            w.newLine();
        }
    }

    static void showHistory() {
        List<String> rows = new ArrayList<>();
        try (BufferedReader r = new BufferedReader(new FileReader(HISTORY))) {
            String line;
            while ((line = r.readLine()) != null) {
                StringTokenizer st = new StringTokenizer(line, ",");
                rows.add(String.format("%-10s %-28s F0 %6s  J %5s%%  S %5s%%  HNR %5s  %s",
                        st.nextToken(), st.nextToken(), st.nextToken(), st.nextToken(),
                        st.nextToken(), st.nextToken(), st.nextToken()));
            }
        } catch (FileNotFoundException e) {
            System.out.println("No history yet.");
            return;
        } catch (IOException e) {
            System.out.println("Cannot read history: " + e.getMessage());
            return;
        }
        printAll("Saved history:", rows);
    }

    static <T> void printAll(String title, List<T> items) {     // generic method
        System.out.println(title);
        for (T item : items) System.out.println("  " + item);
    }
}
