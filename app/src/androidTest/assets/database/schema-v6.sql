-- Archived Room-generated schema from version 6, before removing saved_recipes.
CREATE TABLE IF NOT EXISTS `grocery_items` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `name` TEXT NOT NULL, `quantity` INTEGER NOT NULL, `price` REAL NOT NULL, `category` TEXT NOT NULL, `expiryDate` INTEGER NOT NULL, `threshold` INTEGER NOT NULL, `purchasedDate` INTEGER NOT NULL);
CREATE TABLE IF NOT EXISTS `budget_entries` (`monthYear` TEXT NOT NULL, `limitAmount` REAL NOT NULL, PRIMARY KEY(`monthYear`));
CREATE TABLE IF NOT EXISTS `inventory_logs` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `itemId` INTEGER, `itemName` TEXT NOT NULL, `category` TEXT NOT NULL, `changeType` TEXT NOT NULL, `amountChanged` INTEGER NOT NULL, `unitPriceAtChange` REAL NOT NULL, `timestamp` INTEGER NOT NULL, FOREIGN KEY(`itemId`) REFERENCES `grocery_items`(`id`) ON UPDATE NO ACTION ON DELETE SET NULL );
CREATE INDEX IF NOT EXISTS `index_inventory_logs_itemId` ON `inventory_logs` (`itemId`);
CREATE INDEX IF NOT EXISTS `index_inventory_logs_timestamp` ON `inventory_logs` (`timestamp`);
CREATE INDEX IF NOT EXISTS `index_inventory_logs_changeType` ON `inventory_logs` (`changeType`);
CREATE TABLE IF NOT EXISTS `saved_recipes` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `title` TEXT NOT NULL, `usedIngredients` TEXT NOT NULL, `instructions` TEXT NOT NULL, `prepTimeMinutes` INTEGER NOT NULL, `imageUrl` TEXT NOT NULL, `videoUrl` TEXT NOT NULL, `createdAt` INTEGER NOT NULL);
CREATE TABLE IF NOT EXISTS `shopping_list_items` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `sourceItemId` INTEGER, `itemName` TEXT NOT NULL, `category` TEXT NOT NULL, `quantityNeeded` INTEGER NOT NULL, `quantityLabel` TEXT NOT NULL DEFAULT '', `estimatedUnitPrice` REAL NOT NULL, `addedBy` TEXT NOT NULL DEFAULT 'Auto', `assignedTo` TEXT NOT NULL DEFAULT 'Household', `isChecked` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL);
CREATE UNIQUE INDEX IF NOT EXISTS `index_shopping_list_items_sourceItemId` ON `shopping_list_items` (`sourceItemId`);
CREATE TABLE IF NOT EXISTS `checklist_history` (`id` TEXT NOT NULL, `dishName` TEXT NOT NULL, `servings` INTEGER NOT NULL, `status` TEXT NOT NULL, `created_at` INTEGER NOT NULL, `missingChecklistJson` TEXT NOT NULL, `inventoryDeductedJson` TEXT NOT NULL, `addedShoppingKeysJson` TEXT NOT NULL, PRIMARY KEY(`id`));
CREATE TABLE IF NOT EXISTS room_master_table (id INTEGER PRIMARY KEY,identity_hash TEXT);
INSERT OR REPLACE INTO room_master_table (id,identity_hash) VALUES(42, 'efc205bd6c9f603208bd52fbb74fab5e');
