package tools.mapletools;

import server.life.MonsterStats;
import tools.Pair;

import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * @author RonanLana
 * <p>
 * This application traces missing meso drop data on the underlying DB (that must be
 * defined on the DatabaseConnection file of this project) and generates a
 * SQL file that proposes missing drop entries for the drop_data table.
 * <p>
 * The meso range is calculated accordingly with the target mob stats, such as level
 * and if it's a boss or not, similarly as how it has been done for the actual meso
 * drops.
 */

public class MesoFetcher {
    private static final Path OUTPUT_FILE = ToolConstants.getOutputFile("meso_drop_data.sql");
    private static final boolean PERMIT_MESOS_ON_DOJO_BOSSES = false;
    private static final int MESO_ID = 0;
    private static final int MIN_ITEMS = 4;
    private static final int CHANCE = 400000;

    private static final Map<Integer, Pair<Integer, Integer>> mobRange = new HashMap<>();
    private static PrintWriter printWriter;
    private static Map<Integer, MonsterStats> mobStats;

    private static Pair<Integer, Integer> calcMesoRange90(int level, boolean boss) {
        int minRange, maxRange;

        // MIN range
        minRange = (int) (72.70814714 * Math.exp(0.02284640619 * level));

        // MAX range
        maxRange = (int) (133.8194881 * Math.exp(0.02059225059 * level));

        // boss perks
        if (boss) {
            minRange *= 3;
            maxRange *= 10;
        }

        return new Pair<>(minRange, maxRange);
    }

    private static Pair<Integer, Integer> calcMesoRange(int level, boolean boss) {
        int minRange, maxRange;

        // MIN range
        minRange = (int) (30.32032228 * Math.exp(0.03281144930 * level));

        // MAX range
        maxRange = (int) (44.45878459 * Math.exp(0.03289611686 * level));

        // boss perks
        if (boss) {
            minRange *= 3;
            maxRange *= 10;
        }

        return new Pair<>(minRange, maxRange);
    }

    private static void calcAllMobsMesoRange() {
        System.out.print("Calculating range... ");

        for (Map.Entry<Integer, MonsterStats> mobStat : mobStats.entrySet()) {
            MonsterStats mms = mobStat.getValue();
            Pair<Integer, Integer> mesoRange;

            if (mms.getLevel() < 90) {
                mesoRange = calcMesoRange(mms.getLevel(), mms.isBoss());
            } else {
                mesoRange = calcMesoRange90(mms.getLevel(), mms.isBoss());
            }

            mobRange.put(mobStat.getKey(), mesoRange);
        }

        System.out.println("done!");
    }

    private static void printSqlHeader() {
        printWriter.println(" # SQL File autogenerated from the MapleMesoFetcher feature by Ronan Lana.");
        printWriter.println(" # Generated data takes into account mob stats such as level and boss for the meso ranges.");
        printWriter.println(" # Only mobs with " + MIN_ITEMS + " or more items with no meso entry on the DB it was compiled are presented here.");
        printWriter.println();

        printWriter.println("  INSERT IGNORE INTO drop_data (`dropperid`, `itemid`, `minimum_quantity`, `maximum_quantity`, `questid`, `chance`) VALUES");
    }

    private static void printSqlExceptions() {
        if (!PERMIT_MESOS_ON_DOJO_BOSSES) {
            printWriter.println("\r\n  DELETE FROM drop_data WHERE dropperid >= 9300184 AND dropperid <= 9300215 AND itemid = " + MESO_ID + ";");
        }
    }

    private static void printSqlMobMesoRange(int mobid) {
        Pair<Integer, Integer> mobmeso = mobRange.get(mobid);
        printWriter.println("(" + mobid + ", " + MESO_ID + ", " + mobmeso.left + ", " + mobmeso.right + ", 0, " + CHANCE + "),");
    }

    private static void printSqlMobMesoRangeFinal(int mobid) {
        Pair<Integer, Integer> mobmeso = mobRange.get(mobid);
        printWriter.println("(" + mobid + ", " + MESO_ID + ", " + mobmeso.left + ", " + mobmeso.right + ", 0, " + CHANCE + ");");
    }

    private static void generateMissingMobsMesoRange() {
        System.out.print("Generating missing ranges... ");
        try (Connection con = SimpleDatabaseConnection.getConnection();
        	PreparedStatement ps = con.prepareStatement("SELECT dropperid FROM drop_data WHERE dropperid NOT IN (SELECT DISTINCT dropperid FROM drop_data WHERE itemid = 0) GROUP BY dropperid HAVING count(*) >= " + MIN_ITEMS + ";");
        	ResultSet rs = ps.executeQuery();) {
        	
            List<Integer> existingMobs = new ArrayList<>(200);

            if (rs.isBeforeFirst()) {
                while (rs.next()) {
                    int mobid = rs.getInt(1);

                    if (mobRange.containsKey(mobid)) {
                        existingMobs.add(mobid);
                    }
                }

                if (!existingMobs.isEmpty()) {
                    try (PrintWriter pw = new PrintWriter(Files.newOutputStream(OUTPUT_FILE))) {
                        printWriter = pw;

                        printSqlHeader();

                        for (int i = 0; i < existingMobs.size() - 1; i++) {
                            printSqlMobMesoRange(existingMobs.get(i));
                        }

                        printSqlMobMesoRangeFinal(existingMobs.get(existingMobs.size() - 1));

                        printSqlExceptions();
                    }
                } else {
                    throw new Exception("ALREADY UPDATED");
                }

            } else {
                throw new Exception("ALREADY UPDATED");
            }

            System.out.println("done!");

        } catch (Exception e) {
            if (e.getMessage() != null && e.getMessage().equals("ALREADY UPDATED")) {
                System.out.println("done! The DB is already up-to-date, no file generated.");
            } else {
                e.printStackTrace();
            }
        }
    }

    public static void main(String[] args) {
    	Instant instantStarted = Instant.now();
    	// load mob stats from WZ
        mobStats = MonsterStatFetcher.getAllMonsterStats();

        calcAllMobsMesoRange();
        generateMissingMobsMesoRange();
        Instant instantStopped = Instant.now();
        Duration durationBetween = Duration.between(instantStarted, instantStopped);
        System.out.println("Get elapsed time in milliseconds: " + durationBetween.toMillis());
      	System.out.println("Get elapsed time in seconds: " + (durationBetween.toMillis() / 1000));

    }

}

