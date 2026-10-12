#!/usr/bin/env python3
"""
Crawl bus schedules from Bursakart API for all actual bus stops (t==1).
Output: schedule-v1.json.gz
Format:
{
  "generated": <epoch_seconds>,
  "version": 1,
  "stops": {
    "<stopId>": {
      "1": [["<routeCode>", "<stopTime>"], ...],
      ...
    }
  }
}
"""
import sys
import os
import json
import time
import gzip
import urllib.request
import urllib.error
from concurrent.futures import ThreadPoolExecutor, as_completed

API_URL = "https://bursakartapi.abys-web.com/api/static/ScheduleByRoute"
HEADERS = {
    "Origin": "https://www.bursakart.com.tr",
    "Content-Type": "application/json",
    "Accept": "application/json",
    "User-Agent": "GoIt-ScheduleCrawler/1.0"
}

def fetch_stop_day(stop_id: int, week_day: int, retries: int = 3):
    payload = json.dumps({"stopId": stop_id, "weekDay": week_day}).encode('utf-8')
    for attempt in range(retries):
        try:
            req = urllib.request.Request(API_URL, data=payload, headers=HEADERS, method="POST")
            with urllib.request.urlopen(req, timeout=15) as resp:
                if resp.status == 200:
                    data = json.loads(resp.read().decode('utf-8'))
                    result = data.get("result", [])
                    # Extract [routeCode, stopTime]
                    items = []
                    for entry in result:
                        rc = entry.get("routeCode")
                        st = entry.get("stopTime")
                        if rc and st:
                            items.append([rc.strip(), st.strip()])
                    return stop_id, week_day, items
        except Exception as e:
            time.sleep(0.5 * (attempt + 1))
    return stop_id, week_day, []

def main():
    script_dir = os.path.dirname(os.path.abspath(__file__))
    repo_root = os.path.dirname(script_dir)
    stations_path = os.path.join(repo_root, "app", "src", "main", "assets", "stations.json")
    output_path = os.path.join(repo_root, "schedule-v1.json.gz")
    if len(sys.argv) > 1:
        output_path = sys.argv[1]

    print(f"Loading stops from {stations_path}...")
    with open(stations_path, "r", encoding="utf-8") as f:
        stations_data = json.load(f)

    bus_stops = [s for s in stations_data if s.get("t") == 1]
    print(f"Found {len(bus_stops)} bus stops (t==1).")

    tasks = []
    for stop in bus_stops:
        s_id = stop.get("i")
        if s_id is not None:
            for day in range(1, 8):
                tasks.append((s_id, day))

    total_tasks = len(tasks)
    print(f"Total tasks: {total_tasks} requests (concurrency: 8)...")

    stops_dict = {}
    completed = 0
    errors = 0
    start_time = time.time()

    with ThreadPoolExecutor(max_workers=8) as executor:
        future_to_task = {
            executor.submit(fetch_stop_day, s_id, day): (s_id, day)
            for (s_id, day) in tasks
        }

        for future in as_completed(future_to_task):
            s_id, day, entries = future.result()
            completed += 1
            if entries:
                s_key = str(s_id)
                if s_key not in stops_dict:
                    stops_dict[s_key] = {}
                stops_dict[s_key][str(day)] = entries

            if completed % 1000 == 0 or completed == total_tasks:
                elapsed = time.time() - start_time
                rate = completed / elapsed if elapsed > 0 else 0
                remaining = (total_tasks - completed) / rate if rate > 0 else 0
                print(f"Progress: {completed}/{total_tasks} ({completed*100/total_tasks:.1f}%) | "
                      f"{rate:.1f} req/s | ETA: {remaining/60:.1f}m | Active stops: {len(stops_dict)}")

    output_data = {
        "generated": int(time.time()),
        "version": 1,
        "stops": stops_dict
    }

    print(f"Writing compressed output to {output_path}...")
    json_bytes = json.dumps(output_data, separators=(',', ':')).encode('utf-8')
    with gzip.open(output_path, "wb", compresslevel=9) as f:
        f.write(json_bytes)

    file_size_mb = os.path.getsize(output_path) / (1024 * 1024)
    print(f"Done in {(time.time() - start_time)/60:.1f} minutes! File size: {file_size_mb:.2f} MB. Stops with schedules: {len(stops_dict)}")

if __name__ == "__main__":
    main()
