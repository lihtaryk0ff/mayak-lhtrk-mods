# LHTRK-Mods (`26.3`) — Сервер МАЯК

Набір кастомних серверних Fabric-модів та серверний ресурс-пак для Minecraft-сервера **МАЯК (LHTRK sndct)**.

- **Платформа:** Fabric (Minecraft `26.3`), Java `21`
- **Залежності:** Fabric Loader `>=0.16.0`, Fabric API `0.166.2+26.3`, Text Placeholder API `3.2.0+26.3`
- **Автор:** `lihtaryk`

---

## Модулі

### 1. `lhtrk-access` — Система доступів та захист Маяка
Керує ролями **Гість** і **Гравець** (`subscribers.json`, з автоміграцією legacy-тегу `subscriber`) та захищає світ `mayak:mayak`.
- **Обмеження для Гостей:**
  - Заборонено ламати/ставити блоки та взаємодіяти з ними (єдиний виняток — **двері** за тегом `BlockTags.DOORS`).
  - Заборонено витоптувати грядки (`FarmlandBlockMixin`).
  - Заборонено використовувати предмети, атакувати й взаємодіяти з сутностями.
  - Заборонено підбирати дропнуті предмети (`ItemEntityMixin`) і сфери досвіду (`ExperienceOrbMixin`) без впливу на Гравців поруч.
  - Безсмертя (окрім `GENERIC_KILL` та `/k`), повний голод/повітря, прихований `MINING_FATIGUE` V, пацифікація мобів (`24` блоки) та кріперів (`7` блоків).
  - Автоповернення на спавн Маяка (`config/lhtrk_mayak.json`) через 5 секунд, якщо Гість опинився в іншому вимірі.
- **Команди:** `/sub <player>`, `/unsub <player>`, `/sublist`, `/subreload`, `/k <target>` (OP/консоль), `/stuck` (лише Гості, кулдаун 10 хв).

### 2. `lhtrk-admin` — Адмінська телепортація та спавн
- `/mayak [target]` — телепортує на спавн `mayak:mayak` із `config/lhtrk_mayak.json` (OP).
- `/overworld [target]` — телепортує на спавн `minecraft:overworld` («Великі Землі», OP).
- `/setmayakspawn` — зберігає поточну позицію адміністратора як спавн Маяка в `config/lhtrk_mayak.json` (OP).

### 3. `lhtrk-afk` — Система AFK
Автоматично переводить гравця в AFK після **5 хвилин** бездіяльності (рух, поворот камери, чат, кліки/взаємодії).
- **Сірий нік у табі:** `ServerPlayerMixin` (`getTabListDisplayName` + `UPDATE_DISPLAY_NAME`).
- **Пауза награних годин:** `ServerPlayerMixin` (`awardStat`) блокує нарахування `Stats.PLAY_TIME` під час AFK.
- **Анімація частинок:** орбіта сріблястих частинок `DUST` та іскри `END_ROD` над головою AFK-гравця.
- **Команди:** `/afk` (для себе), `/afk <target>` (для OP/консолі — примусовий перевід у AFK або вихід із нього).

### 4. `lhtrk-night-skipper` — Плавний пропуск ночі
Плавна перемотка ночі до початку доби (`0` тіків — момент світанку з першими променями сонця на шейдерах) через `ServerClockManager`.
- Враховує **лише Гравців**, які **не в AFK** (`!LhtrkAfkMod.isAfk(p)`), у `overworld` та `mayak:mayak`.
- Динамічний поріг сну: старт `50%`, крок `±10%` (межі `20%–90%`), очищення погоди після світанку.

### 5. `lhtrk-placeholders` — Кастомні плейсхолдери
На базі `placeholder-api`:
- `%lhtrk:playtime_ua%` — награний час (`12 год 35 хв`)
- `%lhtrk:sub_badge%` — значок ролі (`✦` / `◇`)
- `%lhtrk:role_hover%` — повний статус (`✦ Гравець` / `◇ Гість`)
- `%lhtrk:world_ua%` — локалізований вимір (`Маяк`, `Великі Землі`, `Незер`, `Енд`)
- `%lhtrk:advancements%` — кількість досягнень
- `%lhtrk:afk_badge%` — бейдж ` [AFK]` сірим кольором
- `%lhtrk:online_players%` — кількість Гравців онлайн

---

## Ресурс-пак (`resourcepack/`)
- `pack_format: 97` (Minecraft `26.3`).
- `assets/mayak/textures/font/logo.png` + `assets/minecraft/font/default.json` — гліф `\uE001` для логотипа **Mayak** у шапці TAB (`Styled Player List`).
- `server-icon.png` — іконка `64×64` для списку серверів.

---

## Збірка

```bash
./gradlew build
```

Усі зібрані `.jar` файли копіюються в `build/mods/`.
