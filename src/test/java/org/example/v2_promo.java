package org.example;

import com.microsoft.playwright.*;
import com.microsoft.playwright.options.*;
import org.junit.jupiter.api.*;

import java.text.SimpleDateFormat;
import java.util.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.awt.*; // для размера экрана
import java.util.List;

public class v2_promo {
    static Playwright playwright;
    static Browser browser;
    static BrowserContext context;
    static Page mainPage;
    static TelegramNotifier tg;

    // ПРОВЕРЬ путь под себя
    private final String screenshotsFolder = "C:\\Users\\zhntm\\IdeaProjects\\11.11.2025\\1XBONUS";
    private final List<String> promoNames = new ArrayList<>();

    @BeforeAll
    static void setUpAll() {
        playwright = Playwright.create();
        browser = playwright.chromium().launch(
                new BrowserType.LaunchOptions()
                        .setHeadless(false)
                        .setArgs(List.of("--start-maximized"))
        );
        context = browser.newContext(
                new Browser.NewContextOptions()
                        .setViewportSize(null)
        );
        mainPage = context.newPage();
        mainPage.setDefaultTimeout(30_000);

        // --- Telegram ---
        String botToken = ConfigHelper.get("telegram.bot.token");
        String chatId = ConfigHelper.get("telegram.chat.id");
        tg = new TelegramNotifier(botToken, chatId);
    }

    @Test
    void openBonusesAndTakeScreenshotsInAllLanguages() {
        long startTime = System.currentTimeMillis();

        // --- Telegram уведомление о старте ---
        tg.sendMessage(
                "🚀 *Старт*: v2\\_promo (десктоп, раздел 1XBONUS)\n"
                        + "• Время: *" + new SimpleDateFormat("HH:mm:ss").format(new Date()) + "*\n"
                        + "• Сайт: [1xbet\\.kz](https://1xbet.kz)\n"
                        + "_Проверка всех доступных акций и создание скриншотов..._"
        );

        try {
            ensureScreenshotsDir();

            mainPage.navigate("https://1xbet.kz/");
            mainPage.waitForLoadState(LoadState.DOMCONTENTLOADED);
            mainPage.waitForTimeout(2000);
            System.out.println("Открыли https://1xbet.kz/");

            // --- Раздел 1XBONUS ---
            mainPage.waitForSelector("a[href='bonus/rules']");
            mainPage.click("a[href='bonus/rules']");
            mainPage.waitForLoadState(LoadState.DOMCONTENTLOADED);
            mainPage.waitForTimeout(1500);

            // --- Кликаем "Все бонусы" ---
            Locator allBonusesBtn = mainPage.locator("button.bonus-navigation-tabs-item-link:has-text('Все бонусы')");
            try {
                allBonusesBtn.waitFor(
                        new Locator.WaitForOptions()
                                .setTimeout(5000)
                                .setState(WaitForSelectorState.VISIBLE)
                );
                allBonusesBtn.click();
            } catch (Exception e) {
                System.out.println("⚠ Не удалось кликнуть 'Все бонусы' обычным способом, пробуем через JS: " + e.getMessage());
                mainPage.evaluate("""
                    Array.from(document.querySelectorAll('button.bonus-navigation-tabs-item-link'))
                        .find(el => el.textContent.includes('Все бонусы'))?.click();
                """);
            }

            // --- Список акций ---
            mainPage.waitForSelector("ul.bonuses-list");
            List<ElementHandle> bonusLinks = mainPage.querySelectorAll("ul.bonuses-list a.bonus-tile");
            if (bonusLinks.isEmpty()) throw new RuntimeException("❌ Не найдено ни одной акции!");

            Locator bonusTitles = mainPage.locator("a.bonus-tile .bonus-tile-content__name div");
            int titlesCount = bonusTitles.count();
            for (int i = 0; i < titlesCount; i++) {
                try {
                    promoNames.add(bonusTitles.nth(i).innerText().trim());
                } catch (Exception ignored) {
                }
            }

            System.out.println("Найдено акций (по названиям): " + promoNames.size());
            System.out.println("Найдено акций (по ссылкам): " + bonusLinks.size());

            // --- Перебор акций ---
            for (int i = 0; i < bonusLinks.size(); i++) {
                String href = bonusLinks.get(i).getAttribute("href");
                if (href == null || href.isBlank()) {
                    System.out.println("⚠ У акции #" + (i + 1) + " нет href, пропускаем");
                    continue;
                }

                String url = href.startsWith("http") ? href : "https://1xbet.kz" + href;
                String promoName = i < promoNames.size() ? promoNames.get(i) : ("Акция #" + (i + 1));

                System.out.println("=== " + promoName + " → " + url);

                Page tab = context.newPage();

                // --- ru ---
                tab.navigate(url);
                waitForPageLoaded(tab, url, i + 1, "ru");
                takeScreenshot(tab, promoName, "ru");

                // --- kz ---
                switchLanguage(tab, "kz");
                waitForPageLoaded(tab, url, i + 1, "kz");
                takeScreenshot(tab, promoName, "kz");

                // --- en ---
                switchLanguage(tab, "en");
                waitForPageLoaded(tab, url, i + 1, "en");
                takeScreenshot(tab, promoName, "en");

                tab.close();
                mainPage.bringToFront();
                mainPage.waitForTimeout(700);
            }

            // --- Telegram отчёт ---
            long elapsed = (System.currentTimeMillis() - startTime) / 1000;
            StringBuilder report = new StringBuilder();
            report.append("✅ *Успешно завершено*: v2\\_promo\n")
                    .append("• Проверено акций: *").append(promoNames.size()).append("*\n\n")
                    .append("📋 *Список акций:*\n");
            for (String name : promoNames) {
                report.append("• ").append(name.replace("-", "\\-")).append("\n");
            }
            report.append("\n📂 *Скриншоты сохранены в:*\n`")
                    .append(getEscapedScreenshotsFolder()).append("`\n")
                    .append("🕒 *Время выполнения:* ").append(elapsed).append(" сек.\n")
                    .append("🌐 [1xbet\\.kz](https://1xbet.kz)");

            tg.sendMessage(report.toString());

        } catch (Exception e) {
            tg.sendMessage("❌ *Ошибка в v2\\_promo*: `" + e.getMessage().replace("_", "\\_") + "`");
            e.printStackTrace();
        }
    }

    // ТВОЙ «старый» метод, один в один
    private void waitForPageLoaded(Page page, String url, int index, String lang) {
        try {
            // Ждём, пока утихнет сеть (SPA, ajax и т.п.)
            page.waitForLoadState(
                    LoadState.NETWORKIDLE,
                    new Page.WaitForLoadStateOptions().setTimeout(30_000)
            );

            // Ждём появления ключевых блоков промо/бонуса/хедера/футера
            page.waitForSelector(
                    "header, footer, .bonus-detail, .promo-detail",
                    new Page.WaitForSelectorOptions()
                            .setTimeout(15_000)
                            .setState(WaitForSelectorState.VISIBLE)
            );

            // Небольшая дополнительная пауза, чтобы всё дорисовалось
            page.waitForTimeout(3000);

            System.out.println("✅ Страница #" + index + " [" + lang + "] загружена: " + url);
        } catch (Exception e) {
            System.out.println("⚠ Ошибка загрузки #" + index + " [" + lang + "]: " + url + " — " + e.getMessage());
            // На всякий случай ещё небольшая пауза, чтобы не делать скриншот совсем пустой страницы
            page.waitForTimeout(3000);
        }
    }

    private void takeScreenshot(Page page, String promoName, String lang) {
        try {
            ensureScreenshotsDir();

            String safeName = promoName
                    .replaceAll("[^a-zA-Z0-9а-яА-Я\\s]", "")
                    .replace(" ", "_");
            String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss").format(new Date());
            String filename = String.format("%s\\%s_%s_%s.png", screenshotsFolder, safeName, lang, timestamp);

            page.screenshot(new Page.ScreenshotOptions()
                    .setPath(Paths.get(filename))
                    .setFullPage(true));

            System.out.println("📸 Скриншот сохранён: " + filename);
        } catch (Exception e) {
            System.out.println("Ошибка скриншота: " + e.getMessage());
        }
    }

    private void switchLanguage(Page page, String lang) {
        try {
            System.out.println("🔁 Меняем язык на: " + lang);

            // убираем возможные модалки/оверлеи
            page.evaluate("document.querySelectorAll('.vfm, .box-modal, .popup, .modal').forEach(el => el.remove());");
            page.waitForTimeout(500);

            Locator langBtn = page.locator("button.header-lang__btn");
            langBtn.waitFor(
                    new Locator.WaitForOptions()
                            .setTimeout(5000)
                            .setState(WaitForSelectorState.VISIBLE)
            );
            langBtn.click();

            String selector = "a.header-lang-list-item-link[data-lng='" + lang + "']";
            Locator target = page.locator(selector);
            target.waitFor(
                    new Locator.WaitForOptions()
                            .setTimeout(5000)
                            .setState(WaitForSelectorState.VISIBLE)
            );
            target.click();

            page.waitForLoadState(
                    LoadState.NETWORKIDLE,
                    new Page.WaitForLoadStateOptions().setTimeout(20_000)
            );
            page.waitForTimeout(1200);

            System.out.println("✅ Язык переключён: " + lang);
        } catch (Exception e) {
            System.out.println("⚠ Не удалось сменить язык на " + lang + ": " + e.getMessage());
        }
    }

    private void ensureScreenshotsDir() {
        try {
            Path dir = Paths.get(screenshotsFolder);
            Files.createDirectories(dir);
        } catch (Exception e) {
            System.out.println("⚠ Не удалось создать папку для скриншотов: " + e.getMessage());
        }
    }

    private String getEscapedScreenshotsFolder() {
        return screenshotsFolder.replace("\\", "\\\\");
    }

    @AfterAll
    static void tearDownAll() {
        System.out.println("Тест завершён ✅ (браузер остаётся открытым)");
    }
}
