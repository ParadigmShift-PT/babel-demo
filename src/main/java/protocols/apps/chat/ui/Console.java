package protocols.apps.chat.ui;

import java.io.IOException;
import java.util.function.Consumer;

import org.jline.reader.EndOfFileException;
import org.jline.reader.LineReader;
import org.jline.reader.LineReaderBuilder;
import org.jline.reader.UserInterruptException;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;

/**
 * A tiny mIRC-style terminal, built on JLine. The point of using JLine (rather
 * than plain {@code System.in}/{@code out}) is the <b>fixed input line</b>: you
 * can keep typing at the bottom while incoming messages scroll in above, without
 * your half-typed line getting scrambled.
 *
 * <p>Two threads touch this:
 * <ul>
 *   <li>the <b>reader thread</b> (started by {@link #start}) blocks on
 *       {@link LineReader#readLine} and hands each entered line to a callback;</li>
 *   <li>any thread (typically Babel's protocol event loop) can call
 *       {@link #printAbove} to display a message — JLine redraws the prompt for us.</li>
 * </ul>
 */
public class Console {

    private final Terminal terminal;
    private final LineReader reader;
    private final String prompt;
    private volatile boolean running = true;

    public Console(String prompt) throws IOException {
        // dumb(true) → if there's no real TTY (e.g. output piped to a file) we
        // degrade gracefully instead of throwing.
        this.terminal = TerminalBuilder.builder().system(true).dumb(true).build();
        this.reader = LineReaderBuilder.builder().terminal(terminal).build();
        this.prompt = prompt;
    }

    /** Print a line above the input prompt (safe to call from any thread). */
    public void printAbove(String line) {
        reader.printAbove(line);
    }

    /**
     * Start the input loop on a daemon thread. {@code onLine} is called for every
     * entered line; {@code onQuit} runs when the user hits Ctrl-C / Ctrl-D or the
     * input stream ends.
     */
    public void start(Consumer<String> onLine, Runnable onQuit) {
        Thread t = new Thread(() -> {
            try {
                while (running) {
                    String line;
                    try {
                        line = reader.readLine(prompt);
                    } catch (UserInterruptException e) { // Ctrl-C
                        break;
                    } catch (EndOfFileException e) {      // Ctrl-D / end of input
                        break;
                    }
                    if (line != null && !line.isBlank()) {
                        onLine.accept(line.trim());
                    }
                }
            } finally {
                onQuit.run();
            }
        }, "chat-console");
        t.setDaemon(true);
        t.start();
    }

    public void stop() {
        running = false;
    }
}
