package gg.nomi.adb;

import static org.junit.Assert.assertEquals;

import org.junit.Assume;
import org.junit.Test;

import java.io.File;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

public class ShellTest {
    private static final String[] VALUES = {
        "",
        "plain",
        "it's",
        "'",
        "''",
        "'\\''",
        "a b  c",
        " leading and trailing ",
        "$HOME ${PATH} $(id) `id`",
        "a;b; echo injected",
        "'; echo injected; '",
        "line one\nline two\n",
        "\n",
        "tab\there",
        "back\\slash\\",
        "glob * ? [a]",
        "& | < > ( ) { } ! # ~",
        "\"double\" quotes",
    };

    @Test
    public void quoteEscapesSingleQuotes() {
        assertEquals("'it'\\''s'", Shell.quote("it's"));
        assertEquals("''", Shell.quote(""));
    }

    @Test
    public void quoteRoundTripsThroughShC() throws Exception {
        String shell = findShell();
        Assume.assumeNotNull(shell);
        for (String value : VALUES) {
            assertEquals(value, run(shell, "printf '%s' " + Shell.quote(value)));
        }
    }

    @Test
    public void joinKeepsWordsApart() throws Exception {
        String shell = findShell();
        Assume.assumeNotNull(shell);
        String output = run(shell, "printf '[%s]' " + Shell.join("a b", "it's", "", "$x;y\nz"));
        assertEquals("[a b][it's][][$x;y\nz]", output);
    }

    private static String run(String shell, String script) throws Exception {
        boolean windows = File.pathSeparatorChar == ';';
        ProcessBuilder builder = windows ? new ProcessBuilder(shell, "-s") : new ProcessBuilder(shell, "-c", script);
        Process process = builder.redirectErrorStream(true).start();
        try (OutputStream in = process.getOutputStream()) {
            if (windows) in.write(script.getBytes(StandardCharsets.UTF_8));
        }
        byte[] output = process.getInputStream().readAllBytes();
        if (!process.waitFor(20, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new AssertionError("sh did not exit");
        }
        assertEquals(0, process.exitValue());
        return new String(output, StandardCharsets.UTF_8);
    }

    private static String findShell() {
        String path = System.getenv("PATH");
        if (path == null) return null;
        for (String directory : path.split(File.pathSeparator)) {
            for (String name : new String[] {"sh", "sh.exe"}) {
                File candidate = new File(directory, name);
                if (candidate.isFile() && candidate.canExecute()) return candidate.getPath();
            }
        }
        return null;
    }
}
