#!/usr/bin/env python3
import concurrent.futures
import gzip
import json
import os
import random
import sys
import time
import urllib.request
import urllib.error

API_URL = "https://bursakartapi.abys-web.com/api/static/ScheduleByRoute"
ORIGIN_HEADER = "https://www.bursakart.com.tr"
MAX_WORKERS = 8
MAX_RETRIES = 3

def fetch_stop_day(stop_id, week_day):
    payload = json.dumps({"stopId": stop_id, "weekDay": week_day}).encode("utf-8")
    req = urllib.request.Request(
        API_URL,
        data=payload,
        headers={
            "Origin": ORIGIN_HEADER,
            "Content-Type": "application/json",
            "Accept": "application/json",
            "User-Agent": "GoItTransit/1.6.0"
        }
    )

    for attempt in range(1, MAX_RETRIES + 1):
        try:
            # Jitter to prevent burst rate-limits
            time.sleep(random.uniform(0.02, 0.08))
            with urllib.request.urlopen(req, timeout=15) as resp:
                if resp.status == 200:
                    data = json.loads(resp.read().decode("utf-8"))
                    res = data.get("result")
                    if isinstance(res, list) and len(res) > 0:
                        entries = []
                        for item in res:
                            rcode = item.get("routeCode")
                            stime = item.get("stopTime")
                            if rcode and stime:
                                entries.append([rcode, stime])
                        return (stop_id, week_day, entries, None)
                    return (stop_id, week_day, [], None)
        except Exception as e:
            if attempt == MAX_RETRIES:
                return (stop_id, week_day, [], str(e))
            time.sleep(0.2 * attempt + random.uniform(0.05, 0.15))
    return (stop_id, week_day, [], "Max retries exceeded")

def main():
    script_dir = os.path.dirname(os.path.abspath(__file__))
    project_root = os.path.dirname(script_dir)
    default_stations_path = os.path.join(project_root, "app", "src", "main", "assets", "stations.json")
    stations_path = sys.argv[1] if len(sys.argv) > 1 else default_stations_path
    output_path = sys.argv[2] if len(sys.argv) > 2 else os.path.join(project_root, "schedule-v1.json.gz")

    if not os.path.exists(stations_path):
        print(f"Error: stations.json not found at {stations_path}")
        sys.exit(1)

    print(f"Loading stations from {stations_path}...")
    with open(stations_path, "r", encoding="utf-8") as f:
        stations = json.load(f)

    # Filter for real transit stops (t == 1)
    stops = [s["i"] for s in stations if s.get("t") == 1]
    total_stops = len(stops)
    print(f"Found {total_stops} transit stops. Preparing 7-day schedule crawl (concurrency={MAX_WORKERS})...")

    # stops_dict: stop_id -> { "1": [...], "2": [...] }
    schedule_data = {
        "generated": int(time.time()),
        "version": 1,
        "stops": {}
    }

    tasks = []
    for s_id in stops:
        for day in range(1, 8):
            tasks.append((s_id, day))

    total_tasks = len(tasks)
    completed_stops = set()
    errors = []
    start_time = time.time()

    print(f"Starting {total_tasks} schedule queries...")

    with concurrent.futures.ThreadPoolExecutor(max_workers=MAX_WORKERS) as executor:
        futures = {executor.submit(fetch_stop_day, s_id, day): (s_id, day) for s_id, day in tasks}
        processed_tasks = 0

        for future in concurrent.futures.as_completed(futures):
            processed_tasks += 1
            s_id, day, entries, err = future.result()

            if err:
                errors.append(f"Stop {s_id} Day {day}: {err}")

            if entries:
                str_sid = str(s_id)
                str_day = str(day)
                if str_sid not in schedule_data["stops"]:
                    schedule_data["stops"][str_sid] = {}
                schedule_data["stops"][str_sid][str_day] = entries

            completed_stops.add(s_id)
            if len(completed_stops) % 500 == 0 and (processed_tasks % 3500 == 0):
                elapsed = time.time() - start_time
                pct = (processed_tasks / total_tasks) * 100
                print(f"Progress: {len(completed_stops)}/{total_stops} stops ({pct:.1f}%), "
                      f"elapsed: {elapsed/60:.1f}m, errors: {len(errors)}")

    print(f"Crawl completed in {(time.time() - start_time)/60:.1f} minutes.")
    print(f"Stops with schedule data: {len(schedule_data['stops'])}")
    if errors:
        print(f"Encountered {len(errors)} transient errors during crawl.")

    print(f"Writing compressed output to {output_path}...")
    json_bytes = json.dumps(schedule_data, separators=(',', ':')).encode("utf-8")
    with gzip.open(output_path, "wb", compresslevel=9) as gz_out:
        gz_out.write(json_bytes)

    out_size_mb = os.path.getsize(output_path) / (1024 * 1024)
    print(f"Done! Output size: {out_size_mb:.2f} MB")

if __name__ == "__main__":
    main()
