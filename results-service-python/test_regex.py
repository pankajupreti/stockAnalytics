"""
Test header regex pattern.
"""
import re

thead = """
      <tr>
        <th class="text"></th>

          <th class="highlight-cell">
            Dec 2022

          </th>

          <th class="">
            Mar 2023

          </th>
"""

# Current pattern
th_pattern = r'<th[^>]*>\s*((?:Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Oct|Nov|Dec)\s+\d{4})'
headers = re.findall(th_pattern, thead, re.IGNORECASE)
print(f"Pattern 1 results: {headers}")

# Alternative pattern - more flexible
th_pattern2 = r'<th[^>]*>\s*(?:<[^>]*>)*\s*((?:Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Oct|Nov|Dec)\s*\d{4})'
headers2 = re.findall(th_pattern2, thead, re.IGNORECASE)
print(f"Pattern 2 results: {headers2}")

# Get all th content
all_ths = re.findall(r'<th[^>]*>(.*?)</th>', thead, re.DOTALL)
print(f"\nAll TH content:")
for th in all_ths:
    clean = th.strip()
    print(f"  '{clean}'")
