/*
	This file is part of the OdinMS Maple Story Server
    Copyright (C) 2008 Patrick Huy <patrick.huy@frz.cc>
		       Matthias Butz <matze@odinms.de>
		       Jan Christian Meyer <vimes@odinms.de>

    Copyleft (L) 2016 - 2019 RonanLana (HeavenMS)

    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU Affero General Public License as
    published by the Free Software Foundation version 3 as published by
    the Free Software Foundation. You may not use, modify or distribute
    this program under any other version of the GNU Affero General Public
    License.

    This program is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU Affero General Public License for more details.

    You should have received a copy of the GNU Affero General Public License
    along with this program.  If not, see <http://www.gnu.org/licenses/>.
*/
package client.processor.npc;

import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteException;
import client.Character;
import client.Client;
import client.autoban.AutobanFactory;
import client.inventory.Inventory;
import client.inventory.InventoryType;
import client.inventory.Item;
import client.inventory.ItemFactory;
import client.inventory.manipulator.InventoryManipulator;
import client.inventory.manipulator.KarmaManipulator;
import config.YamlConfig;
import constants.id.ItemId;
import constants.inventory.ItemConstants;
import database.MapleDBHelper;
import net.server.Server;
import net.server.channel.Channel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import server.DueyPackage;
import server.ItemInformationProvider;
import server.Trade;
import tools.DatabaseConnection;
import tools.PacketCreator;
import tools.Pair;

import java.sql.*;
import java.util.Calendar;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;

/**
 * @author RonanLana - synchronization of Duey modules
 */
public class DueyProcessor {
    private static final Logger log = LoggerFactory.getLogger(DueyProcessor.class);

    public enum Actions {
        TOSERVER_RECV_ITEM(0x00),
        TOSERVER_SEND_ITEM(0x02),
        TOSERVER_CLAIM_PACKAGE(0x04),
        TOSERVER_REMOVE_PACKAGE(0x05),
        TOSERVER_CLOSE_DUEY(0x07),
        TOCLIENT_OPEN_DUEY(0x08),
        TOCLIENT_SEND_ENABLE_ACTIONS(0x09),
        TOCLIENT_SEND_NOT_ENOUGH_MESOS(0x0A),
        TOCLIENT_SEND_INCORRECT_REQUEST(0x0B),
        TOCLIENT_SEND_NAME_DOES_NOT_EXIST(0x0C),
        TOCLIENT_SEND_SAMEACC_ERROR(0x0D),
        TOCLIENT_SEND_RECEIVER_STORAGE_FULL(0x0E),
        TOCLIENT_SEND_RECEIVER_UNABLE_TO_RECV(0x0F),
        TOCLIENT_SEND_RECEIVER_STORAGE_WITH_UNIQUE(0x10),
        TOCLIENT_SEND_MESO_LIMIT(0x11),
        TOCLIENT_SEND_SUCCESSFULLY_SENT(0x12),
        TOCLIENT_RECV_UNKNOWN_ERROR(0x13),
        TOCLIENT_RECV_ENABLE_ACTIONS(0x14),
        TOCLIENT_RECV_NO_FREE_SLOTS(0x15),
        TOCLIENT_RECV_RECEIVER_WITH_UNIQUE(0x16),
        TOCLIENT_RECV_SUCCESSFUL_MSG(0x17),
        TOCLIENT_RECV_PACKAGE_MSG(0x1B);
        final byte code;

        Actions(int code) {
            this.code = (byte) code;
        }

        public byte getCode() {
            return code;
        }
    }

    private static Pair<Integer, Integer> getAccountCharacterIdFromCNAME(String name) {
        Pair<Integer, Integer> ids = new Pair<>(-1, -1);
        try (SQLiteDatabase con = DatabaseConnection.getConnection();
             Cursor ps = con.rawQuery("SELECT id,accountid FROM characters WHERE name = ?", new String[]{name})) {
            if (ps.moveToFirst()) {
                int accountidIdx = ps.getColumnIndex("accountid");
                int idIdx = ps.getColumnIndex("id");
                    ids.left = ps.getInt(accountidIdx);
                    ids.right = ps.getInt(idIdx);
            }
        } catch (SQLiteException e) {
            e.printStackTrace();
        }

        return ids;
    }

    private static void showDueyNotification(Client c, Character player) {
        try (SQLiteDatabase con = DatabaseConnection.getConnection();
             Cursor rs = con.rawQuery("SELECT SenderName, Type FROM dueypackages WHERE ReceiverId = ? AND Checked = 1 ORDER BY Type DESC",
                     new String[]{String.valueOf(player.getId())})) {
            if (rs.moveToFirst()) {
                do {
                    try (Cursor rs2 = con.rawQuery("UPDATE dueypackages SET Checked = 0 where ReceiverId = ?",
                            new String[]{String.valueOf(player.getId())})) {
                        int SenderNameIdx = rs.getColumnIndex("SenderName");
                        int typeIdx = rs.getColumnIndex("Type");
                        c.sendPacket(PacketCreator.sendDueyParcelReceived(rs.getString(SenderNameIdx), rs.getInt(typeIdx) == 1));
                    }
                } while (rs.moveToNext());
            }
        } catch (SQLiteException e) {
            e.printStackTrace();
        }
    }

    private static void deletePackageFromInventoryDB(SQLiteDatabase con, int packageId) throws SQLiteException {
        ItemFactory.DUEY.saveItems(new LinkedList<>(), packageId, con);
    }

    private static void removePackageFromDB(int packageId) {
        try (SQLiteDatabase con = DatabaseConnection.getConnection()) {
            String dueyPackagesTable = "dueypackages";
            String selection = "PackageId = ?";
            String[] selectionArgs = { String.valueOf(packageId) };
            con.delete(dueyPackagesTable, selection, selectionArgs);
            deletePackageFromInventoryDB(con, packageId);
        } catch (SQLiteException e) {
            e.printStackTrace();
        }
    }

    private static DueyPackage getPackageFromDB(Cursor rs) {
        try {
            int PackageIdIdx = rs.getColumnIndex("PackageId");
            int packageId = rs.getInt(PackageIdIdx);

            List<Pair<Item, InventoryType>> dueyItems = ItemFactory.DUEY.loadItems(packageId, false);
            DueyPackage dueypack;

            if (!dueyItems.isEmpty()) {     // in a duey package there's only one item
                dueypack = new DueyPackage(packageId, dueyItems.get(0).getLeft());
            } else {
                dueypack = new DueyPackage(packageId);
            }

            int SenderNameIdx = rs.getColumnIndex("SenderName");
            int MesosIdx = rs.getColumnIndex("Mesos");
            int TimeStampIdx = rs.getColumnIndex("TimeStamp");
            int TypeIdx = rs.getColumnIndex("Type");
            int MessageIdx = rs.getColumnIndex("Message");

            dueypack.setSender(rs.getString(SenderNameIdx));
            dueypack.setMesos(rs.getInt(MesosIdx));
            dueypack.setSentTime(new Timestamp(rs.getLong(TimeStampIdx)), rs.getInt(TypeIdx) != 0);
            dueypack.setMessage(rs.getString(MessageIdx));

            return dueypack;
        } catch (SQLiteException sqle) {
            sqle.printStackTrace();
            return null;
        }
    }

    private static List<DueyPackage> loadPackages(Character chr) {
        List<DueyPackage> packages = new LinkedList<>();
        try (SQLiteDatabase con = DatabaseConnection.getConnection();
             Cursor ps = con.rawQuery("SELECT * FROM dueypackages dp WHERE ReceiverId = ?",
                     new String[]{String.valueOf(chr.getId())})) {
            while (ps.moveToNext()) {
                DueyPackage dueypack = getPackageFromDB(ps);
                if (dueypack == null) {
                    continue;
                }
                packages.add(dueypack);
            }

        } catch (SQLiteException e) {
            e.printStackTrace();
        }

        return packages;
    }

    private static int createPackage(int mesos, String message, String sender, int toCid, boolean quick) {
        ContentValues values = new ContentValues();
        values.put("ReceiverId", toCid);
        values.put("SenderName", sender);
        values.put("Mesos", mesos);
        values.put("TimeStamp", System.currentTimeMillis());
        values.put("Message", message);
        values.put("Type", quick ? 1 : 0);

        try (SQLiteDatabase con = DatabaseConnection.getConnection()) {
            long packageId;
            packageId = con.insert("dueypackages", null, values);
            if (packageId < 1) {
                log.error("Error trying to create package [mesos: {}, sender: {}, quick: {}, receiver chrId: {}]", mesos, sender, quick, toCid);
                return -1;
            }
            return (int) packageId;
        } catch (SQLiteException sqle) {
            sqle.printStackTrace();
        }

        return -1;
    }

    private static boolean insertPackageItem(int packageId, Item item) {
        Pair<Item, InventoryType> dueyItem = new Pair<>(item, InventoryType.getByType(item.getItemType()));
        try (SQLiteDatabase con = DatabaseConnection.getConnection()) {
            ItemFactory.DUEY.saveItems(Collections.singletonList(dueyItem), packageId, con);
            return true;
        } catch (SQLiteException sqle) {
            sqle.printStackTrace();
        }

        return false;
    }

    private static int addPackageItemFromInventory(int packageId, Client c, byte invTypeId, short itemPos, short amount) {
        if (invTypeId > 0) {
            ItemInformationProvider ii = ItemInformationProvider.getInstance();

            InventoryType invType = InventoryType.getByType(invTypeId);
            Inventory inv = c.getPlayer().getInventory(invType);

            Item item;
            inv.lockInventory();
            try {
                item = inv.getItem(itemPos);
                if (item != null && item.getQuantity() >= amount) {
                    if (item.isUntradeable() || ii.isUnmerchable(item.getItemId())) {
                        return -1;
                    }

                    if (ItemConstants.isRechargeable(item.getItemId())) {
                        InventoryManipulator.removeFromSlot(c, invType, itemPos, item.getQuantity(), true);
                    } else {
                        InventoryManipulator.removeFromSlot(c, invType, itemPos, amount, true, false);
                    }

                    item = item.copy();
                } else {
                    return -2;
                }
            } finally {
                inv.unlockInventory();
            }

            KarmaManipulator.toggleKarmaFlagToUntradeable(item);
            item.setQuantity(amount);

            if (!insertPackageItem(packageId, item)) {
                return 1;
            }
        }

        return 0;
    }

    public static void dueySendItem(Client c, byte invTypeId, short itemPos, short amount, int sendMesos, String sendMessage, String recipient, boolean quick) {
        if (c.tryacquireClient()) {
            try {
                if (c.getPlayer().isGM() && c.getPlayer().gmLevel() < YamlConfig.config.server.MINIMUM_GM_LEVEL_TO_USE_DUEY) {
                    c.getPlayer().message("You cannot use Duey to send items at your GM level.");
                    log.info(String.format("GM %s tried to send a package to %s", c.getPlayer().getName(), recipient));
                    c.sendPacket(PacketCreator.sendDueyMSG(Actions.TOCLIENT_SEND_INCORRECT_REQUEST.getCode()));
                    return;
                }

                int fee = Trade.getFee(sendMesos);
                if (sendMessage != null && sendMessage.length() > 100) {
                    AutobanFactory.PACKET_EDIT.alert(c.getPlayer(), c.getPlayer().getName() + " tried to packet edit with Quick Delivery on duey.");
                    log.warn("Chr {} tried to use duey with too long of a text", c.getPlayer().getName());
                    c.disconnect(true, false);
                    return;
                }
                if (!quick) {
                    fee += 5000;
                } else if (!c.getPlayer().haveItem(ItemId.QUICK_DELIVERY_TICKET)) {
                    AutobanFactory.PACKET_EDIT.alert(c.getPlayer(), c.getPlayer().getName() + " tried to packet edit with Quick Delivery on duey.");
                    log.warn("Chr {} tried to use duey with Quick Delivery without a ticket, mesos {} and amount {}", c.getPlayer().getName(), sendMesos, amount);
                    c.disconnect(true, false);
                    return;
                }

                long finalcost = (long) sendMesos + fee;
                if (finalcost < 0 || finalcost > Integer.MAX_VALUE || (amount < 1 && sendMesos == 0)) {
                    AutobanFactory.PACKET_EDIT.alert(c.getPlayer(), c.getPlayer().getName() + " tried to packet edit with duey.");
                    log.warn("Chr {} tried to use duey with mesos {} and amount {}", c.getPlayer().getName(), sendMesos, amount);
                    c.disconnect(true, false);
                    return;
                }

                if(c.getPlayer().getMeso() < finalcost) {
                    c.sendPacket(PacketCreator.sendDueyMSG(Actions.TOCLIENT_SEND_NOT_ENOUGH_MESOS.getCode()));
                    return;
                }

                var accIdCid = getAccountCharacterIdFromCNAME(recipient);
                var recipientAccId = accIdCid.getLeft();
                var recipientCid = accIdCid.getRight();

                if (recipientAccId == -1 || recipientCid == -1) {
                    c.sendPacket(PacketCreator.sendDueyMSG(Actions.TOCLIENT_SEND_NAME_DOES_NOT_EXIST.getCode()));
                    return;
                }

                if (recipientAccId == c.getAccID()) {
                    c.sendPacket(PacketCreator.sendDueyMSG(Actions.TOCLIENT_SEND_SAMEACC_ERROR.getCode()));
                    return;
                }

                if (quick) {
                    InventoryManipulator.removeById(c, InventoryType.CASH, ItemId.QUICK_DELIVERY_TICKET, (short) 1, false, false);
                }

                int packageId = createPackage(sendMesos, sendMessage, c.getPlayer().getName(), recipientCid, quick);
                if (packageId == -1) {
                    c.sendPacket(PacketCreator.sendDueyMSG(Actions.TOCLIENT_SEND_ENABLE_ACTIONS.getCode()));
                    return;
                }
                c.getPlayer().gainMeso((int) -finalcost, false);

                int res = addPackageItemFromInventory(packageId, c, invTypeId, itemPos, amount);
                if (res == 0) {
                    c.sendPacket(PacketCreator.sendDueyMSG(Actions.TOCLIENT_SEND_SUCCESSFULLY_SENT.getCode()));
                } else if (res > 0) {
                    c.sendPacket(PacketCreator.sendDueyMSG(Actions.TOCLIENT_SEND_ENABLE_ACTIONS.getCode()));
                } else {
                    c.sendPacket(PacketCreator.sendDueyMSG(Actions.TOCLIENT_SEND_INCORRECT_REQUEST.getCode()));
                }

                Client rClient = null;
                int channel = c.getWorldServer().find(recipient);
                if (channel > -1) {
                    Channel rcserv = c.getWorldServer().getChannel(channel);
                    if (rcserv != null) {
                        Character rChr = rcserv.getPlayerStorage().getCharacterByName(recipient);
                        if (rChr != null) {
                            rClient = rChr.getClient();
                        }
                    }
                }

                if (rClient != null && rClient.isLoggedIn() && !rClient.getPlayer().isAwayFromWorld()) {
                    showDueyNotification(rClient, rClient.getPlayer());
                }
            } finally {
                c.releaseClient();
            }
        }
    }

    public static void dueyRemovePackage(Client c, int packageid, boolean playerRemove) {
        if (c.tryacquireClient()) {
            try {
                removePackageFromDB(packageid);
                c.sendPacket(PacketCreator.removeItemFromDuey(playerRemove, packageid));
            } finally {
                c.releaseClient();
            }
        }
    }

    public static void dueyClaimPackage(Client c, int packageId) {
        if (c.tryacquireClient()) {
            try {
                try {
                    DueyPackage dp = null;
                    try (SQLiteDatabase con = DatabaseConnection.getConnection();
                         Cursor ps = con.rawQuery("SELECT * FROM dueypackages dp WHERE PackageId = ?", new String[]{String.valueOf(packageId)})) {
                        if (ps.moveToNext()) {
                            dp = getPackageFromDB(ps);
                        }
                    }

                    if (dp == null) {
                        c.sendPacket(PacketCreator.sendDueyMSG(Actions.TOCLIENT_RECV_UNKNOWN_ERROR.getCode()));
                        log.warn("Chr {} tried to receive package from duey with id {}", c.getPlayer().getName(), packageId);
                        return;
                    }

                    if (dp.isDeliveringTime()) {
                        c.sendPacket(PacketCreator.sendDueyMSG(Actions.TOCLIENT_RECV_UNKNOWN_ERROR.getCode()));
                        return;
                    }

                    Item dpItem = dp.getItem();
                    if (dpItem != null) {
                        if (!c.getPlayer().canHoldMeso(dp.getMesos())) {
                            c.sendPacket(PacketCreator.sendDueyMSG(Actions.TOCLIENT_RECV_UNKNOWN_ERROR.getCode()));
                            return;
                        }

                        if (!InventoryManipulator.checkSpace(c, dpItem.getItemId(), dpItem.getQuantity(), dpItem.getOwner())) {
                            int itemid = dpItem.getItemId();
                            if (ItemInformationProvider.getInstance().isPickupRestricted(itemid) && c.getPlayer().getInventory(ItemConstants.getInventoryType(itemid)).findById(itemid) != null) {
                                c.sendPacket(PacketCreator.sendDueyMSG(Actions.TOCLIENT_RECV_RECEIVER_WITH_UNIQUE.getCode()));
                            } else {
                                c.sendPacket(PacketCreator.sendDueyMSG(Actions.TOCLIENT_RECV_NO_FREE_SLOTS.getCode()));
                            }

                            return;
                        } else {
                            InventoryManipulator.addFromDrop(c, dpItem, false);
                        }
                    }

                    c.getPlayer().gainMeso(dp.getMesos(), false);

                    dueyRemovePackage(c, packageId, false);
                } catch (SQLiteException e) {
                    e.printStackTrace();
                }
            } finally {
                c.releaseClient();
            }
        }
    }

    public static void dueySendTalk(Client c, boolean quickDelivery) {
        if (c.tryacquireClient()) {
            try {
                long timeNow = System.currentTimeMillis();
                if (timeNow - c.getPlayer().getNpcCooldown() < YamlConfig.config.server.BLOCK_NPC_RACE_CONDT) {
                    c.sendPacket(PacketCreator.enableActions());
                    return;
                }
                c.getPlayer().setNpcCooldown(timeNow);

                if (quickDelivery) {
                    c.sendPacket(PacketCreator.sendDuey(0x1A, null));
                } else {
                    c.sendPacket(PacketCreator.sendDuey(0x8, loadPackages(c.getPlayer())));
                }
            } finally {
                c.releaseClient();
            }
        }
    }

    public static void dueyCreatePackage(Item item, int mesos, String sender, int recipientCid) {
        int packageId = createPackage(mesos, null, sender, recipientCid, false);
        if (packageId != -1) {
            insertPackageItem(packageId, item);
        }
    }

    public static void runDueyExpireSchedule() {
        Calendar c = Calendar.getInstance();
        c.add(Calendar.DATE, -30);
        final Timestamp ts = new Timestamp(c.getTime().getTime());

        try (SQLiteDatabase con = DatabaseConnection.getConnection()) {
            List<Integer> toRemove = new LinkedList<>();
            try (Cursor ps = con.rawQuery("SELECT `PackageId` FROM dueypackages WHERE `TimeStamp` < ?", new String[]{String.valueOf(ts)})) {

                if (ps.moveToNext()) {
                    int PackageIdIdx = ps.getColumnIndex("PackageId");
                    toRemove.add(ps.getInt(PackageIdIdx));
                }
            }

            for (Integer pid : toRemove) {
                removePackageFromDB(pid);
            }

            String selection = "TimeStamp < ?";
            String[] whereArgs = { String.valueOf(ts.getTime()) };
            con.delete("dueypackages", selection, whereArgs);
        } catch (SQLiteException e) {
            e.printStackTrace();
        }
    }
}
