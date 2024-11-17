import java.io.*;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.ArrayList;
import java.util.Random;
import java.util.Vector;

public class Fuzzer {
    boolean nonZeroFlag = false;

    public static void main(String[] args) {
        if (args.length != 1) {
            System.err.println("Usage: java Fuzzer.java \"<command_to_fuzz>\"");
            System.exit(1);
        }
        String commandToFuzz = args[0];
        String workingDirectory = "./";

        if (!Files.exists(Paths.get(workingDirectory, commandToFuzz))) {
            throw new RuntimeException("Could not find command '" + commandToFuzz + "'.");
        }

        ProcessBuilder builder = getProcessBuilderForCommand(commandToFuzz, workingDirectory);
        System.out.printf("Command: %s\n", builder.command());

        String seedInput = generateInputString();

        Random rand = new Random();
        Vector<int[]> tags = findTags(seedInput);
        /*
        System.err.println("Tags found in seed input:");
        for (int[] tag : tags) {
            System.err.println(seedInput.substring(tag[0], tag[1] + 1));
        }*/

        List<Function<String, String>> mutators = List.of(
            //*
            // Add up 65 random a-z characters before and after a random tag
            input -> {
                int[] tag = tags.get(rand.nextInt(tags.size()));
                String res = input.substring(0, tag[0]) + generateRandomString(65, 26, 'a')
                        + input.substring(tag[0], tag[1] + 1) + generateRandomString(65, 26, 'a') 
                        + input.substring(tag[1] + 1);
                return res;
            },
            // Add an attribute with random value of length of 17 to a random tag
            input -> {
                int[] tag = tags.get(rand.nextInt(tags.size()));
                return input.substring(0, tag[1]) + " attr=\"" 
                        + generateRandomString(17, 26, 'a') + "\"" + input.substring(tag[1]);
            },
            // Add an attribute with length of 17 and random value of length 4 to a random tag
            input -> {
                int[] tag = tags.get(rand.nextInt(tags.size()));
                return input.substring(0, tag[1]) + " attr_" + generateRandomString(12, 26, 'a') + "=\"" 
                        + generateRandomString(4, 26, 'a') + "\"" + input.substring(tag[1]);
            },
            // Add 17 attributes with empty values and a duplicate to a random tag
            input -> {
                int[] tag = tags.get(rand.nextInt(tags.size()));
                String result = input.substring(0, tag[1]);
                for (int i = 0; i < 17; i++) {
                    result += " attr" + i + "=";
                }
                result += " attr" + 16 + "=";
                result += input.substring(tag[1]);
                return result;
            },
            // Change String to be uppercase
            input -> input.toUpperCase(),
            // Shuffle the input
            input -> {
                List<Character> chars = input.chars().mapToObj(e -> (char) e).collect(Collectors.toList());
                Collections.shuffle(chars);
                StringBuilder sb = new StringBuilder(chars.size());
                chars.forEach(sb::append);
                return sb.toString();
            },
            // Replace a random part of the input with a random string
            input -> {
                int start = rand.nextInt(input.length());
                int end = rand.nextInt(input.length());
                if (start > end) {
                    int temp = start;
                    start = end;
                    end = temp;
                }
                return input.substring(0, start) + generateRandomString(end - start, 10000, 0) + input.substring(end);
            },
            // Add a random tag into a random tag
            input -> {
                int[] tag = tags.get(rand.nextInt(tags.size()));
                String tagName = generateRandomString(5, 26, 'a');
                return input.substring(0, tag[0]) + "<" + tagName 
                        + ">" + input.substring(tag[0], tag[1] + 1) + "</" + tagName
                        + ">" + input.substring(tag[1] + 1);
            },
            // Chain multiple the valid file 100 times
            input -> {
                StringBuilder result = new StringBuilder();
                for (int i = 0; i < 100; i++) {
                    result.append(input);
                }
                return result.toString();
            },
            // Add 20 chars in front of tagname
            input -> {
                int[] tag = tags.get(rand.nextInt(tags.size()));
                return input.substring(0, tag[0]) + generateRandomString(20, 26, 'a') 
                        + input.substring(tag[0], tag[1] + 1) + input.substring(tag[1] + 1);
            },
            // Change < to << and > to >>
            input -> {
                input = input.replace("<", "<<");
                input = input.replace(">", ">>");
                return input;
            }
            /*
            // long attribute
            input -> input.replace("<div", "<div atr1=\"0123456789\""),
            // incorrect closing tag
            input -> input.replace("</html>", "</p>"),
            // missing opening tag
            input -> input.replace("<html>", ""),
            // missing closing tag
            input -> input.replace("</html>", ""),
            // interchanged closing tag
            input -> {
                input = input.replace("<html", "<html><p> abc");
                input = input.replace("</html>", "</html></p>");
                return input;},
            // empty
            input -> {return "";},
            // two copies of the same file
            input -> {return input + input;},
            // misspelled tag
            input -> input.replace("<html", "<httml"),
            // replace tag
            input -> input.replace("<html", "<img></img>"),
            // replace tag with self-closing tag
            input -> input.replace("<html", "<img/>"),
            // change case of tag
            input -> input.replace("<html", "<HTML"),
            // add some attributes
            input -> input.replace("<div", "<div atr1=\"1\", atr2=\"2\""),
            // add some attributes with no value
            input -> input.replace("<div", "<div atr1= atr2="),
            // add attribute with special characters
            input -> input.replace("<div", "<div at\"r&1=\"1\""),
            // change charset to broken value
            input -> input.replace("UTF-8", "xxxxxx"),
            // add nonexistent tag
            input -> input.replace("<html", "<xxx> abc </xxx>"),
            // add some special characters
            input -> input.replace("</div>", "\0\1\2\3\4\5\6\7\b\t\f\r </div>"),
            // add comment
            input -> input.replace("<html>", "<html><!-- comment  -->"),
            // add whitespace in tag
            input -> input.replace("<html>", "<html \t\n\r\f>"),
            // remove html tags
            input -> {
                input = input.replace("html", "");
                input = input.replace("/html", "");
                return input;
            }
            //*/
        );

        List<String> mutatedInputs = getMutatedInputs(seedInput, mutators);

        // If we don't need to run all the mutations if we find a failure
        // we could System.exit(1) in the runCommand method and keep it static.
        /*
        Fuzzer fuzzer = new Fuzzer();
        fuzzer.runCommand(builder, seedInput, mutatedInputs);
        if (fuzzer.nonZeroFlag) {
            System.exit(1);
        }
        */
    }

    private static ProcessBuilder getProcessBuilderForCommand(String command, String workingDirectory) {
        ProcessBuilder builder = new ProcessBuilder();
        boolean isWindows = System.getProperty("os.name").toLowerCase().startsWith("windows");
        if (isWindows) {
            builder.command("cmd.exe", "/c", command);
        } else {
            builder.command("sh", "-c", command);
        }
        builder.directory(new File(workingDirectory));
        builder.redirectErrorStream(true); // redirect stderr to stdout
        return builder;
    }

    private void runCommand(ProcessBuilder builder, String seedInput, List<String> mutatedInputs) {
        Stream.concat(Stream.of(seedInput), mutatedInputs.stream()).forEach(
            input -> { 
                try {
                    Process p = builder.start();
                    BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(p.getOutputStream()));
                    writer.write(input);
                    writer.flush();
                    writer.close();
                    try {
                        int exitCode = p.waitFor();
                        if (exitCode != 0) {
                            nonZeroFlag = true;
                            System.err.println("--------------------");
                            System.err.println("Failure with input:");
                            System.err.println("--");
                            System.err.println(input);
                            System.err.println("--");
                            System.out.println("Error (Code " + exitCode + "):\n" + readStreamIntoString(p.getInputStream()));
                        }
                        /*
                        else {
                            System.out.println("--------------------");
                            System.out.println("Success with input:");
                            System.out.println("--");
                            System.out.println(input);
                            System.out.println("--");
                            System.out.println("Result:\n" + readStreamIntoString(p.getInputStream()));
                        } //*/
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                        System.out.println("Error during waitFor");
                    } 
                } catch (IOException e) {
                    e.printStackTrace();
                    System.out.println("Error running command");
                }
            }
        );
    }

    private static String readStreamIntoString(InputStream inputStream) {
        BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream));
        return reader.lines()
                .map(line -> line + System.lineSeparator())
                .collect(Collectors.joining());
    }

    private static List<String> getMutatedInputs(String seedInput, Collection<Function<String, String>> mutators) {
        List<String> mutatedInputs = new ArrayList<>();
        for (Function<String, String> func : mutators) {
            String seed = seedInput;
            mutatedInputs.add(func.apply(seed));
        }
        return mutatedInputs;
    }

    private static String generateInputString() {
        String resultString = "<!DOCTYPE html>\n";
        resultString += "<html>\n";
        resultString += "\t<head>\n";
        resultString += "\t\t<meta charset=\"UTF-8\">\n";
        resultString += "\t</head>\n";
        resultString += "\t<div attr=\"attr\">\n";
        resultString += "<p> Hey there! </p>\n";
        resultString += "<a href=\"abc.de\"> Link </a>\n";
        resultString += "\t\tdata: ";

        resultString += generateRandomString(20, 127, 0);

        resultString += "\n\t</div>\n";
        resultString += "\t<script src=\"index.js\"></script>\n";
        resultString += "</html>";
        return resultString;
    }

    private static String generateRandomString(int length, int range, int offset) {
        Random rand = new Random();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < length; i++) {
            sb.append((char) (rand.nextInt(range + 1) + offset));
        }
        return sb.toString();
    }

    // Find all tags in the input string
    private static Vector<int[]> findTags(String input) {
        Vector<int[]> tags = new Vector<int[]>();
        int start = 0;
        while (start < input.length()) {
            int[] tag = new int[2];
            tag[0] = input.indexOf('<', start);
            if (tag[0] == -1) {
                break;
            }
            tag[1] = input.indexOf('>', tag[0]);
            if (tag[1] == -1) {
                break;
            }
            tags.add(tag);
            start = tag[1] + 1;
        }
        return tags;
    }
}
