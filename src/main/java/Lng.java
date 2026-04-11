import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.zip.GZIPInputStream;

public class Lng {

    static class DSU {
        private final int[] parent;

        public DSU(int n) {
            parent = new int[n];
            for (int i = 0; i < n; i++) {
                parent[i] = i;
            }
        }

        public int find(int i) {
            if (parent[i] == i) {
                return i;
            }
            return parent[i] = find(parent[i]);
        }

        public void union(int i, int j) {
            int rootI = find(i);
            int rootJ = find(j);
            if (rootI != rootJ) {
                parent[rootI] = rootJ;
            }
        }
    }

    private static String createFeatureKey(int columnIndex, String value) {
        return columnIndex + ":" + value;
    }

    private static int readFileAndProcess(String filePath, List<String> uniqueLines, Map<String, Integer> lineToIndex) throws IOException {
        int currentUniqueLineIndex = 0;

        try (InputStream fileStream = new FileInputStream(filePath);
             InputStream is = filePath.endsWith(".gz") ? new GZIPInputStream(fileStream) : fileStream;
             BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {

            String line;
            int addedUniqueLinesCount = 0;

            while ((line = reader.readLine()) != null) {

                if (line.contains(";")) {
                    String[] parts = line.split(";", -1);

                    for (String part : parts) {
                        if (part.length() < 2 || !part.startsWith("\"") || !part.endsWith("\"")) {
                            break;
                        }
                    }
                }

                if (!lineToIndex.containsKey(line)) {
                    uniqueLines.add(line);
                    lineToIndex.put(line, currentUniqueLineIndex++);
                    addedUniqueLinesCount++;
                }
            }

            return addedUniqueLinesCount;
        } catch (FileNotFoundException e) {
            System.err.println("Файл не найден: " + filePath);
            throw e;
        } catch (IOException e) {
            System.err.println("Ошибка ввода-вывода при чтении файла: " + e.getMessage());
            throw e;
        }
    }

    private static void buildDSU(List<String> uniqueLines, DSU dsu, Map<String, Integer> featureToLineIndex) {
        int numberOfUniqueLines = uniqueLines.size();
        for (int i = 0; i < numberOfUniqueLines; i++) {
            String currentLine = uniqueLines.get(i);
            String[] quotedParts = currentLine.split(";", -1);

            for (int colIndex = 0; colIndex < quotedParts.length; colIndex++) {
                String quotedValue = quotedParts[colIndex];

                String value = quotedValue.substring(1, quotedValue.length() - 1);

                if (!value.isEmpty()) {
                    String featureKey = createFeatureKey(colIndex, value);
                    if (featureToLineIndex.containsKey(featureKey)) {
                        dsu.union(i, featureToLineIndex.get(featureKey));
                    } else {
                        featureToLineIndex.put(featureKey, i);
                    }
                }
            }
        }
    }


    public static void main(String[] args) {
        if (args.length == 0) {
            System.err.println("Использование: java -jar <имя_jar_файла> <полный_путь_к_входному_файлу>");
            System.exit(1);
        }

        long startTime = System.currentTimeMillis();
        String filePath = args[0];

        List<String> uniqueLines = new ArrayList<>();
        Map<String, Integer> lineToIndex = new HashMap<>();
        Map<String, Integer> featureToLineIndex = new HashMap<>();
        int numberOfUniqueValidLines;

        try {
            numberOfUniqueValidLines = readFileAndProcess(filePath, uniqueLines, lineToIndex);

            if (numberOfUniqueValidLines == 0) {
                System.out.println("Входной файл '" + filePath + "' пуст или не содержит корректных строк для обработки согласно формату \"<val1>\";\"<val2>\";...");

                System.out.println("Количество групп с более чем одним элементом: 0");
                System.out.println("\nРезультаты сгруппированы в файле: result.txt");
                try (PrintWriter writer = new PrintWriter("result.txt", StandardCharsets.UTF_8)) {
                    writer.println(0);
                    writer.println();
                }
                System.out.println("Время выполнения: " + (System.currentTimeMillis() - startTime) / 1000.0 + " сек");
                return;
            }

            DSU dsu = new DSU(numberOfUniqueValidLines);
            buildDSU(uniqueLines, dsu, featureToLineIndex);

            Map<Integer, List<Integer>> groupsMap = new HashMap<>();
            for (int i = 0; i < numberOfUniqueValidLines; i++) {
                int root = dsu.find(i);
                groupsMap.computeIfAbsent(root, k -> new ArrayList<>()).add(i);
            }

            List<List<Integer>> sortedGroups = groupsMap.values().stream()
                    .sorted((g1, g2) -> Integer.compare(g2.size(), g1.size()))
                    .collect(Collectors.toList());
            long groupsWithMoreThanOne = sortedGroups.stream()
                    .filter(group -> group.size() > 1)
                    .count();

            try (PrintWriter writer = new PrintWriter("result.txt", StandardCharsets.UTF_8)) {
                writer.println(groupsWithMoreThanOne);
                writer.println();

                int groupLabelCounter = 1;
                for (List<Integer> groupIndices : sortedGroups) {
                    writer.println("Группа " + groupLabelCounter++);
                    for (int lineIndex : groupIndices) {
                        writer.println(uniqueLines.get(lineIndex));
                    }
                    writer.println();
                }
            }

            long endTime = System.currentTimeMillis();
            System.out.println("Количество групп с более чем одним элементом: " + groupsWithMoreThanOne);
            System.out.println("Время выполнения: " + (endTime - startTime) / 1000.0 + " сек");

        } catch (FileNotFoundException e) {
            System.err.println("Ошибка: Файл не найден по пути '" + filePath + "'.");
            System.err.println("Пожалуйста, убедитесь, что путь к файлу указан верно и файл существует.");
            System.exit(1);
        } catch (IOException e) {
            System.err.println("Ошибка при чтении файла '" + filePath + "'.");
            System.err.println("Возможно, файл поврежден, имеет неверный формат (например, .gz файл не является gzip-архивом) или возникла другая проблема ввода-вывода.");
            System.err.println("Детали ошибки: " + e.getMessage());
            System.exit(1);
        } catch (Exception e) {
            System.err.println("Произошла непредвиденная ошибка:");
            e.printStackTrace();
            System.exit(1);
        }
    }
}