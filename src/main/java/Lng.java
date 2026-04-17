import org.apache.commons.compress.archivers.ArchiveException;
import org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry;
import org.apache.commons.compress.archivers.sevenz.SevenZFile;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.zip.GZIPInputStream;


public class Lng {

    private static class ExtractedFileInfo implements Closeable {
        private final InputStream inputStream;
        private final String filename;
        private final SevenZFile sevenZFile;

        ExtractedFileInfo(InputStream inputStream, String filename, SevenZFile sevenZFile) {
            this.inputStream = inputStream;
            this.filename = filename;
            this.sevenZFile = sevenZFile;
        }

        public InputStream getInputStream() {
            return inputStream;
        }

        public String getFilename() {
            return filename;
        }

        @Override
        public void close() throws IOException {
            try {
                if (inputStream != null) {
                    inputStream.close();
                }
            } finally {
                if (sevenZFile != null) {
                    try {
                        sevenZFile.close();
                    } catch (IOException e) {
                        System.err.println("Ошибка при закрытии SevenZFile: " + e.getMessage());
                    }
                }
            }
        }
    }

    static class DSU {
        private int[] parent;
        private int numSets;

        public DSU(int n) {
            parent = new int[n];
            numSets = n;
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
                numSets--;
            }
        }

        public int getNumSets() {
            return numSets;
        }
    }

    private static String createFeatureKey(int columnIndex, String value) {
        return columnIndex + ":" + value;
    }

    private static ExtractedFileInfo getExtractedFileInfo(String filePath) throws IOException {
        File file = new File(filePath);

        if (filePath.toLowerCase().endsWith(".gz")) {
            return new ExtractedFileInfo(new GZIPInputStream(new FileInputStream(file)), file.getName(), null);
        } else if (filePath.toLowerCase().endsWith(".7z")) {
            return getExtractedFileInfoFrom7zArchiveCommonsCompress(file);
        } else {
            return new ExtractedFileInfo(new FileInputStream(file), file.getName(), null);
        }
    }

    private static ExtractedFileInfo getExtractedFileInfoFrom7zArchiveCommonsCompress(File archiveFile) throws IOException {
        SevenZFile sevenZFile = null;
        InputStream fileEntryStream = null;
        String extractedFilename = null;

        try {
            sevenZFile = new SevenZFile(archiveFile);
            List<SevenZArchiveEntry> entries = new ArrayList<>();
            for (SevenZArchiveEntry entry : sevenZFile.getEntries()) {
                entries.add(entry);
            }

            if (entries.isEmpty()) {
                throw new IOException("В 7z архиве '" + archiveFile.getName() + "' не найдены файлы.");
            }

            SevenZArchiveEntry entryToExtract = null;
            for (SevenZArchiveEntry entry : entries) {
                if (!entry.isDirectory()) {
                    entryToExtract = entry;
                    extractedFilename = entry.getName();
                    break;
                }
            }

            if (entryToExtract == null) {
                throw new IOException("В 7z архиве '" + archiveFile.getName() + "' не найден ни один файл.");
            }

            fileEntryStream = sevenZFile.getInputStream(entryToExtract);
            return new ExtractedFileInfo(fileEntryStream, extractedFilename, sevenZFile);

        } catch (IOException e) {
            if (sevenZFile != null) {
                try {
                    sevenZFile.close();
                } catch (IOException ignored) {}
            }
            throw new IOException("Ошибка при работе с 7z архивом '" + archiveFile.getName() + "': " + e.getMessage(), e);
        }
    }

    private static int readFileAndProcess(ExtractedFileInfo extractedInfo, List<String> uniqueLines, Map<String, Integer> lineToIndex) throws IOException {
        String filename = extractedInfo.getFilename();
        boolean isCsv = filename != null && filename.toLowerCase().endsWith(".csv");

        try (InputStream fileStream = extractedInfo.getInputStream()) {
            if (isCsv) {
                return parseCsvFormat(fileStream, filename, uniqueLines, lineToIndex);
            } else {
                return parseOriginalFormat(fileStream, filename, uniqueLines, lineToIndex);
            }
        }
    }

    private static int parseOriginalFormat(InputStream inputStream, String filename, List<String> uniqueLines, Map<String, Integer> lineToIndex) throws IOException {
        int currentUniqueLineIndex = 0;
        int linesReadCount = 0;
        int skippedLinesCount = 0;
        int addedUniqueLinesCount = 0;

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                linesReadCount++;
                boolean isValidLine = true;
                String skipReason = "";

                if (!line.contains(";")) {
                    isValidLine = false;
                    skipReason = "нет разделителя ';'";
                } else {
                    String[] parts = line.split(";", -1);
                    for (String part : parts) {
                        if (part.length() < 2 || !part.startsWith("\"") || !part.endsWith("\"")) {
                            isValidLine = false;
                            skipReason = "некорректное поле: " + part;
                            break;
                        }
                    }
                }

                if (!isValidLine) {
                    skippedLinesCount++;
                    continue;
                }

                if (!lineToIndex.containsKey(line)) {
                    uniqueLines.add(line);
                    lineToIndex.put(line, currentUniqueLineIndex++);
                    addedUniqueLinesCount++;
                }
            }
        }

        return addedUniqueLinesCount;
    }

    private static int parseCsvFormat(InputStream inputStream, String filename, List<String> uniqueLines, Map<String, Integer> lineToIndex) throws IOException {
        int currentUniqueLineIndex = 0;
        int addedUniqueLinesCount = 0;

        CSVFormat csvFormat = CSVFormat.DEFAULT.builder()
                .setDelimiter(',')
                .setQuote(null)
                .setEscape(null)
                .setRecordSeparator("\n")
                .setIgnoreEmptyLines(true)
                .setTrim(true)
                .build();

        try (Reader reader = new InputStreamReader(inputStream, StandardCharsets.UTF_8);
             CSVParser csvParser = new CSVParser(reader, csvFormat)) {

            for (CSVRecord csvRecord : csvParser) {
                StringBuilder reconstructedLine = new StringBuilder();
                boolean firstField = true;
                for (String field : csvRecord) {
                    if (!firstField) reconstructedLine.append(',');
                    reconstructedLine.append(field);
                    firstField = false;
                }
                String processedLine = reconstructedLine.toString();

                if (!lineToIndex.containsKey(processedLine)) {
                    uniqueLines.add(processedLine);
                    lineToIndex.put(processedLine, currentUniqueLineIndex++);
                    addedUniqueLinesCount++;
                }
            }
        }
        return addedUniqueLinesCount;
    }

    private static void buildDSU(List<String> uniqueLines, DSU dsu, Map<String, Integer> featureToLineIndex) throws IOException {
        featureToLineIndex.clear();
        for (int i = 0; i < uniqueLines.size(); i++) {
            String line = uniqueLines.get(i);

            if (line.contains(";")) {
                String[] parts = line.split(";", -1);
                for (int colIndex = 0; colIndex < parts.length; colIndex++) {
                    String part = parts[colIndex];
                    if (part.length() >= 2 && part.startsWith("\"") && part.endsWith("\"")) {
                        String value = part.substring(1, part.length() - 1);
                        value = value.replace("\"\"", "\"");
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
            else if (line.contains(",")) {
                String[] parts = line.split(",", -1);
                for (int colIndex = 0; colIndex < parts.length; colIndex++) {
                    String value = parts[colIndex].trim();

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
    }

    public static void main(String[] args) {
        // --- ДОБАВЛЕНО: Замер времени выполнения с самого начала ---
        long startTime = System.currentTimeMillis();

        if (args.length == 0) {
            System.err.println("Использование: java -jar <jar> <file>");
            System.exit(1);
        }

        String filePath = args[0];
        List<String> uniqueLines = new ArrayList<>();
        Map<String, Integer> lineToIndex = new HashMap<>();
        Map<String, Integer> featureToLineIndex = new HashMap<>();
        int numberOfUniqueValidLines = 0;

        // --- Здесь мы получаем startTime ---
        // startTime уже объявлена выше

        try (ExtractedFileInfo extractedInfo = getExtractedFileInfo(filePath)) {
            numberOfUniqueValidLines = readFileAndProcess(extractedInfo, uniqueLines, lineToIndex);

            if (numberOfUniqueValidLines == 0) {
                // Если нет данных, выводим пустые результаты и статистику
                try (PrintWriter writer = new PrintWriter("result.txt", StandardCharsets.UTF_8)) {
                    writer.println(0); // 0 групп с более чем одним элементом
                }
                // --- ВЫВОД СТАТИСТИКИ при раннем выходе ---
                long endTime = System.currentTimeMillis();
                System.out.println("----------------------------------------------");
                System.out.println("Обработка завершена (нет данных для анализа).");
                System.out.println("Количество групп с более чем одним элементом: 0");
                System.out.println("Время выполнения: " + (endTime - startTime) / 1000.0 + " секунд");
                System.out.println("----------------------------------------------");
                return; // Завершаем выполнение
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
                    if (groupIndices.size() > 1) {
                        writer.println("Группа " + groupLabelCounter++);
                        for (int lineIndex : groupIndices) {
                            writer.println(uniqueLines.get(lineIndex));
                        }
                        writer.println();
                    }
                }
            }
            long endTime = System.currentTimeMillis();
            System.out.println("Количество групп с более чем одним элементом: " + groupsWithMoreThanOne);
            System.out.println("Время выполнения: " + (endTime - startTime) / 1000.0 + " секунд");

        } catch (Exception e) {
            e.printStackTrace();
            System.exit(1);
        }
    }
}