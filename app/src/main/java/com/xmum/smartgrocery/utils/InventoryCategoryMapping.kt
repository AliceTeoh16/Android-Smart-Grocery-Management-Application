package com.xmum.smartgrocery.utils

import java.text.Normalizer

/** Ordered product rules shared by voice defaults and photo-response correction.
 * Keep the server equivalent in backend/inventory-category-mapping.js in sync;
 * both are checked against inventory-category-cases.json.
 */
internal object InventoryCategoryMapping {
    private fun words(value: String) = "\\b(?:$value)\\b"
    private fun rule(category: String, englishMalay: String, chinese: String) = category to
        Regex(words(englishMalay) + "|(?:$chinese)", RegexOption.IGNORE_CASE)

    // Specific products precede ingredient words: dish soap, cream crackers, orange juice,
    // peanut butter and frozen chicken must not be classified by a single ingredient.
    private val rules = listOf(
        rule("Personal Care",
            "menstrual cups?|(?:makeup|cosmetic|beauty) sponges?|makeup brush(?:es)?|hairbrush(?:es)?|hair brush(?:es)?|combs?|hand cream|face cream|facial cream|facial cleanser|face cleanser|serums?|lipsticks?|lip balms?|mascara|eyeliners?|nail polish|nail clippers?|cotton pads?|cotton buds?|cotton swabs?|perfumes?|cologne|body spray|hair oil|beard oil|shaving (?:cream|foam)|hair gel|hair dye|mouthwash|baby wipes|baby diapers?|adult diapers?|sikat rambut|minyak rambut|gincu|pencuci muka|krim tangan|minyak wangi|tuala wanita",
            "月经杯|月經杯|美妆蛋|美妝蛋|化妆刷|化妝刷|发刷|髮刷|梳子|护手霜|護手霜|面霜|洗面奶|洗面乳|精华液|精華液|口红|口紅|唇膏|睫毛膏|指甲油|化妆棉|化妝棉|棉签|棉籤|香水|剃须膏|剃鬚膏|漱口水|卫生巾|衛生巾|纸尿裤|紙尿褲"),
        rule("Household Essentials",
            "soap dispensers?|soap dishes|toothbrush holders?|shampoo dispensers?|laundry baskets?|disinfecting wipes|surface wipes|batter(?:y|ies)|light bulbs?|candles?|adhesive tape|alumini?um foil|cling (?:film|wrap)|baking paper|parchment paper|storage bags?|zip(?:lock|per) bags?|dish racks?|hangers?|ironing boards?|bateri|mentol|lilin",
            "皂液器|肥皂盒|牙刷架|洗衣篮|洗衣籃|消毒湿巾|消毒濕巾|电池|電池|灯泡|燈泡|蜡烛|蠟燭|胶带|膠帶|铝箔|鋁箔|保鲜膜|保鮮膜|衣架"),
        rule("Beverages", "(?:cups?|mugs?|glass(?:es)?) of (?:(?:hot|iced|cold|brewed) )?(?:tea|coffee|water|juice)|cawan (?:teh|kopi)|segelas (?:teh|kopi)", "一杯(?:茶|咖啡)|茶水"),
        rule("Snacks & Sweets", "(?:peanut butter|chocolate) cups", "花生酱巧克力杯|花生醬巧克力杯"),
        rule("Household Essentials",
            "tea ?cups?|coffee cups?|cups?|mugs?|drinking glass(?:es)?|tumblers?|empty bottles?|reusable water bottles?|plates?|bowls?|cutlery|spoons?|forks?|food containers?|cawan|mangkuk|pinggan|sudu|garpu",
            "茶杯|咖啡杯|杯子|水杯|碗|盘子|盤子|餐具|勺子|叉子|保鲜盒|保鮮盒"),
        rule("Household Essentials",
            "toilet paper|tissues?|paper towels?|detergents?|dishwashing(?: liquid)?|dish soap|laundry|fabric (?:softener|conditioner)|bleach|disinfectants?|(?:trash|garbage|bin) bags?|sponges?|cleaners?|(?:rubber|cleaning|household) gloves?|cleaning cloths?|microfib(?:er|re) cloths?|dustpans?|brooms?|mops?|plungers?|toilet brush(?:es)?|scrub(?:bing)? brush(?:es)?|waste\\s?bins?|trash cans?|rubbish bins?|tisu(?: tandas)?|pencuci (?:pinggan|pakaian)|detergen|pelembut pakaian|beg sampah|sarung tangan getah|kain mikrofiber|penyodok sampah|penyapu|pelocok|berus tandas",
            "洗衣|洗碗|洗洁精|洗潔精|卫生纸|衛生紙|垃圾袋|清洁剂|清潔劑|拖把|扫帚|掃帚|抹布|橡胶手套|橡膠手套|马桶刷|馬桶刷"),
        rule("Personal Care",
            "shampoos?|conditioners?|toothpaste|toothbrush(?:es)?|deodorants?|body wash|shower gels?|lotions?|sunscreen|razors?|skincare|hand soap|hand wash|hand saniti[sz]er|face wash|moisturi[sz]ers?|dental floss|sanitary pads?|tampons?|soap|syampu|perapi rambut|ubat gigi|berus gigi|pencuci badan|sabun mandi|losyen|pencukur|sabun tangan",
            "洗发|洗髮|护发|護髮|牙膏|牙刷|沐浴|洗手液|润肤|潤膚|乳液|防晒|防曬|香皂|肥皂"),
        rule("Frozen Foods", "frozen|ice[ -]?cream|ais krim|sejuk beku|beku", "冷冻|冷凍|冰淇淋|雪糕"),
        rule("Dairy", "(?:almond|oat|soy|soya) milk", "杏仁奶|燕麦奶|燕麥奶|豆奶"),
        rule("Spices",
            "spices?|cumin|cinnamon|paprika|turmeric|seasonings?|salt|black pepper|white pepper|(?:chilli|chili|garlic|onion|curry) powder|dried (?:herbs|basil|oregano)|garam|rempah|jintan|kayu manis|kunyit|lada hitam",
            "香料|调味粉|調味粉|孜然|肉桂|姜黄|薑黃|黑胡椒|盐|鹽"),
        rule("Pantry", "peanut butter|chocolate spread|cocoa powder|(?:soy|fish|oyster|tomato) sauce|kicap|sos tiram", "花生酱|花生醬|可可粉|酱油|醬油|蚝油|蠔油"),
        rule("Snacks & Sweets",
            "biscuits?|cookies?|crackers?|chips|crisps|popcorn|cand(?:y|ies)|sweets|(?:chocolates?)(?! milk)|nuts?|almonds?|cashews?|peanuts?|walnuts?|pistachios?|biskut|coklat(?! susu)|kacang",
            "饼干|餅乾|巧克力(?!奶)|薯片|坚果|堅果|糖果"),
        rule("Beverages", "juices?|coffee|tea|water|soda|jus|kopi|teh|minuman|air mineral|air minuman", "果汁|咖啡|茶|饮料|飲料|矿泉水|礦泉水"),
        rule("Dairy", "milk|eggs?|yog[hu]?urt|cheese|butter|cream|susu|telur|keju", "牛奶|鸡蛋|雞蛋|酸奶|奶酪|黄油|黃油|巧克力奶"),
        rule("Produce",
            "apples?|bananas?|oranges?|tomato(?:es)?|carrots?|onions?|garlic|potato(?:es)?|lettuce|spinach|broccoli|cabbage|berries|avocados?|bell peppers?|mint leaves|dragon ?fruits?|papayas?|pears?|mango(?:es)?|lemons?|limes?|mushrooms?|cucumbers?|leafy greens|buah|pisang|epal|lobak|bawang|kentang|sayur",
            "苹果|蘋果|香蕉|橙|番茄|西红柿|胡萝卜|胡蘿蔔|洋葱|洋蔥|土豆|马铃薯|蒜|蔬菜|青椒|火龙果|火龍果|木瓜|芒果|柠檬|檸檬|蘑菇|黄瓜|黃瓜"),
        rule("Meat & Seafood", "chicken|beef|pork|lamb|meat|fish|salmon|tuna|prawns?|shrimp|seafood|ayam|daging|ikan|udang", "鸡肉|雞肉|牛肉|猪肉|豬肉|羊肉|鱼|魚|虾|蝦"),
        rule("Bakery", "bread|buns?|bagels?|croissants?|roti", "面包|麵包|馒头|饅頭"),
        rule("Pantry", "rice|pasta|noodles?|flour|sugar|oil|sauce|beans?|lentils?|oats?|beras|mi|mee|tepung|gula|minyak", "大米|米饭|面条|麵條|面粉|糖|食用油")
    )

    fun match(name: String): String? {
        val normalized = Normalizer.normalize(name, Normalizer.Form.NFKC)
            .replace(Regex("[‐‑–—-]"), " ").replace(Regex("\\s+"), " ").trim()
        return rules.firstOrNull { it.second.containsMatchIn(normalized) }?.first
    }
}
