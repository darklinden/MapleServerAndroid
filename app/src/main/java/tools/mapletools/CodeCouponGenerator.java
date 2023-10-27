package tools.mapletools;

import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteException;
import android.database.sqlite.SQLiteStatement;
import tools.Pair;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.*;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static java.util.concurrent.TimeUnit.HOURS;

/**
 * @author RonanLana
 * <p>
 * This application parses the coupon descriptor XML file and automatically generates
 * code entries on the DB reflecting the descriptions found. Parse time relies on the
 * sum of coupon codes created and amount of current codes on DB.
 * <p>
 * Estimated parse time: 2 minutes (for 100 code entries)
 */
public class CodeCouponGenerator {
    private static final Path INPUT_FILE = ToolConstants.getInputFile("CouponCodes.img.xml");
    private static final int INITIAL_STRING_LENGTH = 250;
    private static final SQLiteDatabase con = SimpleDatabaseConnection.getConnection();

    private static final List<CodeCouponDescriptor> activeCoupons = new ArrayList<>();
    private static final Set<String> usedCodes = new HashSet<>();
    private static final List<Pair<Integer, Integer>> itemList = new ArrayList<>();

    private static BufferedReader bufferedReader = null;
    private static long currentTime;
    private static String name;
    private static boolean active;
    private static int quantity;
    private static int duration;
    private static int maplePoint;
    private static int nxCredit;
    private static int nxPrepaid;
    private static Pair<Integer, Integer> item;
    private static List<Integer> generatedKeys;
    private static byte status;

    private static void resetCouponPackage() {
        name = null;
        active = false;
        quantity = 1;
        duration = 7;
        maplePoint = 0;
        nxCredit = 0;
        nxPrepaid = 0;
        itemList.clear();
    }

    private static String getName(String token) {
        int i, j;
        char[] dest;
        String d;

        i = token.lastIndexOf("name");
        i = token.indexOf("\"", i) + 1; //lower bound of the string
        j = token.indexOf("\"", i);     //upper bound

        dest = new char[INITIAL_STRING_LENGTH];
        try {
            token.getChars(i, j, dest, 0);
        } catch (StringIndexOutOfBoundsException e) {
            // do nothing
            return "";
        } catch (Exception e) {
            System.out.println("error in: " + token + "");
            e.printStackTrace();
            try {
                Thread.sleep(100000000);
            } catch (Exception ex) {
            }
        }

        return new String(dest).trim();
    }

    private static String getValue(String token) {
        int i, j;
        char[] dest;
        String d;

        i = token.lastIndexOf("value");
        i = token.indexOf("\"", i) + 1; //lower bound of the string
        j = token.indexOf("\"", i);     //upper bound

        dest = new char[INITIAL_STRING_LENGTH];
        token.getChars(i, j, dest, 0);

        d = new String(dest);
        return (d.trim());
    }

    private static void forwardCursor(int st) {
        String line = null;

        try {
            while (status >= st && (line = bufferedReader.readLine()) != null) {
                simpleToken(line);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private static void simpleToken(String token) {
        if (token.contains("/imgdir")) {
            status -= 1;
        } else if (token.contains("imgdir")) {
            status += 1;
        }
    }

    private static void translateToken(String token) {
        if (token.contains("/imgdir")) {
            status -= 1;

            if (status == 1) {
                if (active) {
                    activeCoupons.add(new CodeCouponDescriptor(name, quantity, duration, maplePoint, nxCredit, nxPrepaid, itemList));
                }

                resetCouponPackage();
            } else if (status == 3) {
                itemList.add(item);
            }
        } else if (token.contains("imgdir")) {
            status += 1;

            if (status == 4) {
                item = new Pair<>(-1, -1);
            } else if (status == 2) {
                String d = getName(token);

                System.out.println("  Reading coupon '" + d + "'");
                name = d;
            }
        } else {
            String d = getName(token);

            if (status == 2) {
                switch (d) {
                    case "active":
                        if (Integer.parseInt(getValue(token)) == 0) {
                            forwardCursor(status);
                            resetCouponPackage();
                        } else {
                            active = true;
                        }
                        break;

                    case "quantity":
                        quantity = Integer.parseInt(getValue(token));
                        break;
                    case "duration":
                        duration = Integer.parseInt(getValue(token));
                        break;
                    case "maplePoint":
                        maplePoint = Integer.parseInt(getValue(token));
                        break;
                    case "nxCredit":
                        nxCredit = Integer.parseInt(getValue(token));
                        break;
                    case "nxPrepaid":
                        nxPrepaid = Integer.parseInt(getValue(token));
                        break;
                }
            } else if (status == 4) {
                switch (d) {
                    case "count":
                        item.right = Integer.valueOf(getValue(token));
                        break;
                    case "id":
                        item.left = Integer.valueOf(getValue(token));
                        break;
                }
            }
        }
    }

    private static class CodeCouponDescriptor {
        protected String name;
        protected int quantity, duration;
        protected int nxCredit, maplePoint, nxPrepaid;
        protected List<Pair<Integer, Integer>> itemList;

        protected CodeCouponDescriptor(String name, int quantity, int duration, int maplePoint, int nxCredit, int nxPrepaid, List<Pair<Integer, Integer>> itemList) {
            this.name = name;
            this.quantity = quantity;
            this.duration = duration;
            this.maplePoint = maplePoint;
            this.nxCredit = nxCredit;
            this.nxPrepaid = nxPrepaid;

            this.itemList = new ArrayList<>(itemList);
        }
    }

    private static String randomizeCouponCode() {
        return Long.toHexString(Double.doubleToLongBits(Math.random())).substring(0, 15);
    }

    private static String generateCouponCode() {
        String newCode;
        do {
            newCode = randomizeCouponCode();
        } while (usedCodes.contains(newCode));

        usedCodes.add(newCode);
        return newCode;
    }

    private static List<Integer> getGeneratedKeys(SQLiteDatabase ps) throws SQLiteException {
        if (generatedKeys == null) {
            generatedKeys = new ArrayList<>();

            Cursor cursor = ps.rawQuery("SELECT last_insert_rowid() AS id", null);

            if (cursor.moveToFirst()) {
                int columnIndex = cursor.getColumnIndex("id");
                do {
                    generatedKeys.add(cursor.getInt(columnIndex));
                } while (cursor.moveToNext());
            }
            cursor.close();
        }

        return generatedKeys;
    }

    private static void commitCodeCouponDescription(CodeCouponDescriptor recipe) throws SQLiteException {
        if (recipe.quantity < 1) {
            return;
        }

        System.out.println("  Generating coupon '" + recipe.name + "'");
        generatedKeys = null;

        ContentValues values = new ContentValues();
        long expiration = currentTime + HOURS.toMillis(recipe.duration);
        values.put("expiration", expiration);

        for (int i = 0; i < recipe.quantity; i++) {
            values.put("code", generateCouponCode());
            con.insertWithOnConflict("nxcode", null, values, SQLiteDatabase.CONFLICT_IGNORE);
        }
        List<Integer> keys = getGeneratedKeys(con);

        con.beginTransaction();
        SQLiteStatement ps2 = con.compileStatement("INSERT INTO nxcode_items (codeid, type, item, quantity) VALUES (?, ?, ?, ?)");

        if (!recipe.itemList.isEmpty()) {
            for (Pair<Integer, Integer> p : recipe.itemList) {
                ps2.bindLong(2, 5);
                ps2.bindLong(3, p.getLeft());
                ps2.bindLong(4, p.getRight());

                for (Integer codeid : keys) {
                    ps2.bindLong(1, codeid);
                    ps2.execute();
                    ps2.clearBindings();
                }
            }
        }


        if (recipe.nxCredit > 0) {
            ps2.bindLong(3, 0);
            ps2.bindLong(2, 0);
            ps2.bindLong(4, recipe.nxCredit);

            for (Integer codeid : keys) {
                ps2.bindLong(1, codeid);
                ps2.execute();
                ps2.clearBindings();
            }
        }

        if (recipe.maplePoint > 0) {
            ps2.bindLong(3, 0);
            ps2.bindLong(2, 1);
            ps2.bindLong(4, recipe.maplePoint);

            for (Integer codeid : keys) {
                ps2.bindLong(1, codeid);
                ps2.execute();
                ps2.clearBindings();
            }
        }

        if (recipe.nxPrepaid > 0) {
            ps2.bindLong(3, 0);
            ps2.bindLong(2, 2);
            ps2.bindLong(4, recipe.nxPrepaid);

            for (Integer codeid : keys) {
                ps2.bindLong(1, codeid);
                ps2.execute();
                ps2.clearBindings();
            }
        }
        ps2.close();
        con.setTransactionSuccessful();
        con.endTransaction();
    }

    private static void loadUsedCouponCodes() throws SQLiteException {
        Cursor ps = con.rawQuery("SELECT code FROM nxcode", null);
        if (ps != null && ps.moveToFirst()) {
            do {
                int codeIdx = ps.getColumnIndex("code");
                if (codeIdx != -1) {
                    String code = ps.getString(codeIdx);
                    usedCodes.add(code);
                }
            } while (ps.moveToNext());
        }
        ps.close();
    }

    private static void generateCodeCoupons(Path file) throws IOException {
        try (BufferedReader br = Files.newBufferedReader(file); con;) {
            bufferedReader = br;
            resetCouponPackage();
            status = 0;

            System.out.println("Reading XML coupon information...");
            String line;
            while ((line = bufferedReader.readLine()) != null) {
                translateToken(line);
            }
            System.out.println();

            System.out.println("Loading DB coupon codes...");
            loadUsedCouponCodes();
            System.out.println();

            System.out.println("Saving generated coupons...");
            currentTime = System.currentTimeMillis();
            for (CodeCouponDescriptor ccd : activeCoupons) {
                commitCodeCouponDescription(ccd);
            }
            System.out.println();
            System.out.println("Done.");

        } catch (SQLiteException e) {
            e.printStackTrace();
        }
    }

    public static void main(String[] args) {
        try {
            generateCodeCoupons(INPUT_FILE);
        } catch (IOException ex) {
            System.out.println("Error reading file '" + INPUT_FILE.toAbsolutePath() + "'");
        }
    }
}
