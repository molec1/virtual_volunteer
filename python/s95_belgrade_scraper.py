import csv
import time
import re
import argparse
from typing import Optional, List, Dict, Set
from urllib.parse import urljoin

import requests
import browser_cookie3
from bs4 import BeautifulSoup

BASE = "https://s95.rs"
EVENT_URL = f"{BASE}/events/belgrade"

USER_AGENT = (
    "Mozilla/5.0 (X11; Linux x86_64) "
    "AppleWebKit/537.36 (KHTML, like Gecko) "
    "Chrome/123.0.0.0 Safari/537.36"
)


def clean_text(s: Optional[str]) -> Optional[str]:
    if s is None:
        return None
    s = re.sub(r"\s+", " ", s).strip()
    return s or None


def make_session() -> requests.Session:
    session = requests.Session()
    session.headers.update(
        {
            "User-Agent": USER_AGENT,
            "Accept": "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
            "Accept-Language": "en-US,en;q=0.9,ru;q=0.8,sr;q=0.7",
            "Cache-Control": "no-cache",
            "Pragma": "no-cache",
            "Upgrade-Insecure-Requests": "1",
            "Referer": BASE,
        }
    )

    # Try Chrome first, then Chromium
    cookie_jar = None
    last_err = None

    for loader in [browser_cookie3.chrome, browser_cookie3.chromium]:
        try:
            cookie_jar = loader(domain_name="s95.rs")
            break
        except Exception as e:
            last_err = e

    if cookie_jar is None:
        raise RuntimeError(
            "Could not read Chrome/Chromium cookies. "
            "Make sure the browser is installed, closed, or that your cookie store is accessible."
        ) from last_err

    session.cookies.update(cookie_jar)
    return session


def fetch(session: requests.Session, url: str, referer: Optional[str] = None, timeout: int = 30) -> str:
    headers = {}
    if referer:
        headers["Referer"] = referer

    response = session.get(url, headers=headers, timeout=timeout)
    response.raise_for_status()
    return response.text


def save_debug(path: str, text: str) -> None:
    with open(path, "w", encoding="utf-8") as f:
        f.write(text)


def parse_activity_links(html: str, limit: int) -> List[str]:
    soup = BeautifulSoup(html, "lxml")
    links = []

    for a in soup.select("a[href*='/activities/']"):
        href = a.get("href")
        if not href:
            continue
        full = urljoin(BASE, href)
        if full not in links:
            links.append(full)

    # fallback: date-looking anchors
    if not links:
        for a in soup.find_all("a", href=True):
            txt = clean_text(a.get_text(" ", strip=True))
            href = a.get("href")
            if not href:
                continue
            if txt and re.match(r"\d{2}\.\d{2}\.\d{4}", txt):
                full = urljoin(BASE, href)
                if "/activities/" in full and full not in links:
                    links.append(full)

    def activity_id(url: str) -> int:
        m = re.search(r"/activities/(\d+)", url)
        return int(m.group(1)) if m else -1

    links = sorted(links, key=activity_id, reverse=True)
    return links[:limit]


def parse_athlete_links(activity_html: str) -> List[str]:
    # Try to stay inside "Protokol" section if present
    start = activity_html.find("## Protokol")
    end = activity_html.find("## Volonteri")

    html_slice = activity_html
    if start != -1:
        if end != -1 and end > start:
            html_slice = activity_html[start:end]
        else:
            html_slice = activity_html[start:]

    soup = BeautifulSoup(html_slice, "lxml")
    links = []

    for a in soup.select("a[href*='/athletes/']"):
        href = a.get("href")
        if not href:
            continue
        full = urljoin(BASE, href)
        if full not in links:
            links.append(full)

    # fallback to whole page
    if not links:
        soup_all = BeautifulSoup(activity_html, "lxml")
        for a in soup_all.select("a[href*='/athletes/']"):
            href = a.get("href")
            if not href:
                continue
            full = urljoin(BASE, href)
            if full not in links:
                links.append(full)

    return links


def parse_athlete_page(html: str, athlete_url: str) -> Dict[str, Optional[str]]:
    soup = BeautifulSoup(html, "lxml")
    page_text = soup.get_text(" ", strip=True)

    athlete_id = None
    m = re.search(r"/athletes/(\d+)", athlete_url)
    if m:
        athlete_id = m.group(1)

    name = None

    h1 = soup.find("h1")
    if h1:
        h1_text = clean_text(h1.get_text(" ", strip=True))
        if h1_text and "S95" not in h1_text:
            name = h1_text

    if not name:
        title = soup.find("title")
        if title:
            title_text = clean_text(title.get_text(" ", strip=True))
            if title_text:
                title_text = re.sub(r"\s*\|\s*S95.*$", "", title_text, flags=re.IGNORECASE)
                name = title_text

    barcode = None
    m = re.search(r"\bA\d{6,}\b", page_text)
    if m:
        barcode = m.group(0)

    avatar_url = None

    og_image = soup.find("meta", attrs={"property": "og:image"})
    if og_image and og_image.get("content"):
        avatar_url = urljoin(BASE, og_image["content"])

    if not avatar_url:
        twitter_image = soup.find("meta", attrs={"name": "twitter:image"})
        if twitter_image and twitter_image.get("content"):
            avatar_url = urljoin(BASE, twitter_image["content"])

    if not avatar_url:
        for img in soup.find_all("img"):
            src = img.get("src")
            if not src:
                continue
            src_low = src.lower()
            if any(token in src_low for token in ["avatar", "athlete", "profile", "photo", "user"]):
                avatar_url = urljoin(BASE, src)
                break

    return {
        "athlete_id": athlete_id,
        "athlete_url": athlete_url,
        "name": name,
        "barcode": barcode,
        "avatar_url": avatar_url,
    }


def scrape(limit: int = 10, delay: float = 0.6):
    session = make_session()

    print("Fetching event page with browser cookies...")
    event_html = fetch(session, EVENT_URL, referer=BASE)
    save_debug("debug_event.html", event_html)

    activity_urls = parse_activity_links(event_html, limit)

    if not activity_urls:
        print("No activities found. Saved debug_event.html")
        print("Open it and check whether you got a real page or a forbidden/challenge page.")
        return [], []

    print("Activities found:", len(activity_urls))
    for u in activity_urls:
        print("  ", u)

    athlete_urls = set()  # type: Set[str]

    for idx, activity_url in enumerate(activity_urls, 1):
        print("[activity {}/{}] {}".format(idx, len(activity_urls), activity_url))
        try:
            html = fetch(session, activity_url, referer=EVENT_URL)
            save_debug("debug_activity_{}.html".format(idx), html)

            links = parse_athlete_links(html)
            before = len(athlete_urls)
            athlete_urls.update(links)
            print(
                "  athlete links on page: {}, unique total: {} (+{})".format(
                    len(links),
                    len(athlete_urls),
                    len(athlete_urls) - before,
                )
            )
        except Exception as e:
            print("  skipped due to error:", e)

        time.sleep(delay)

    rows = []
    athlete_urls_sorted = sorted(
        athlete_urls,
        key=lambda u: int(re.search(r"/athletes/(\d+)", u).group(1))
        if re.search(r"/athletes/(\d+)", u) else 0
    )

    print("Unique athletes:", len(athlete_urls_sorted))

    for idx, athlete_url in enumerate(athlete_urls_sorted, 1):
        print("[athlete {}/{}] {}".format(idx, len(athlete_urls_sorted), athlete_url))
        try:
            html = fetch(session, athlete_url, referer=activity_urls[0])
            row = parse_athlete_page(html, athlete_url)
            rows.append(row)
        except Exception as e:
            print("  skipped due to error:", e)

        time.sleep(delay)

    return activity_urls, rows


def save_csv(rows: List[Dict[str, Optional[str]]], path: str) -> None:
    fieldnames = [
        "athlete_id",
        "athlete_url",
        "name",
        "barcode",
        "avatar_url",
    ]
    with open(path, "w", newline="", encoding="utf-8") as f:
        writer = csv.DictWriter(f, fieldnames=fieldnames)
        writer.writeheader()
        writer.writerows(rows)


def save_txt(lines: List[str], path: str) -> None:
    with open(path, "w", encoding="utf-8") as f:
        for line in lines:
            f.write(line + "\n")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--limit", type=int, default=10)
    parser.add_argument("--delay", type=float, default=0.6)
    parser.add_argument("--csv", type=str, default="s95_belgrade_athletes.csv")
    parser.add_argument("--activities-txt", type=str, default="s95_belgrade_activities.txt")
    args = parser.parse_args()

    activity_urls, rows = scrape(limit=args.limit, delay=args.delay)

    save_csv(rows, args.csv)
    save_txt(activity_urls, args.activities_txt)

    print()
    print("Saved CSV:", args.csv)
    print("Saved activities list:", args.activities_txt)
    print("Rows:", len(rows))


if __name__ == "__main__":
    main()