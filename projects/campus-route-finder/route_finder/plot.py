"""Draw the campus graph with a route highlighted (matplotlib)."""

import math
from pathlib import Path

import matplotlib

matplotlib.use("Agg")
import matplotlib.pyplot as plt  # noqa: E402
from matplotlib.lines import Line2D  # noqa: E402

CATEGORY_STYLE = {  # marker, color
    "dining":         ("o", "#d9822b"),
    "library":        ("s", "#2f6db5"),
    "student_center": ("D", "#7a4fb5"),
    "academic":       ("^", "#2e8b57"),
    "bus_stop":       ("P", "#555b66"),
    "waypoint":       ("X", "#999999"),
}
ROUTE_COLOR = "#e0413a"

# Labels that would overlap a neighbor are nudged (dx, dy in degrees).
LABEL_OFFSETS = {
    "library-of-science-medicine": (0.0, 0.0005),
    "science-buildings": (-0.0004, 0.0002),
    "busch-dining-hall": (0.0004, -0.0004),
    "livingston-dining-commons": (-0.0004, 0.0002),
    "james-dickson-carr-library": (-0.0004, -0.0003),
    "lucy-stone-hall": (0.0004, 0.0003),
    "tillett-hall": (0.0004, -0.0005),
    "the-yard": (-0.0004, -0.0005),
    "scott-hall": (0.0004, -0.0001),
}


def plot_route(graph, route, out_path):
    on_route = set(zip(route.path, route.path[1:])) if route else set()
    on_route |= {(b, a) for a, b in on_route}

    fig, ax = plt.subplots(figsize=(10, 11))

    for a, b, meters, kind in graph.edges():
        la, lb = graph.locations[a], graph.locations[b]
        if (a, b) in on_route:
            ax.plot([la.lon, lb.lon], [la.lat, lb.lat], color=ROUTE_COLOR, lw=3.5, zorder=3,
                    solid_capstyle="round")
        else:
            ax.plot([la.lon, lb.lon], [la.lat, lb.lat], color="#b8bcc4",
                    lw=1.6 if kind == "connector" else 0.9,
                    ls="--" if kind == "connector" else "-", zorder=1)
        if kind == "connector":
            ax.annotate(f"{meters:,.0f} m", ((la.lon + lb.lon) / 2, (la.lat + lb.lat) / 2),
                        xytext=(5, 0), textcoords="offset points", fontsize=7.5, color="#6b7280")

    route_nodes = set(route.path) if route else set()
    for loc in graph.locations.values():
        marker, color = CATEGORY_STYLE[loc.category]
        ax.scatter(loc.lon, loc.lat, marker=marker, s=60 if loc.id in route_nodes else 38,
                   color=color, edgecolor=ROUTE_COLOR if loc.id in route_nodes else "white",
                   linewidth=1.5, zorder=4)
        dx, dy = LABEL_OFFSETS.get(loc.id, (0.0004, 0.0))
        ax.text(loc.lon + dx, loc.lat + dy, loc.label, fontsize=7.5, zorder=5,
                ha="right" if dx < 0 else "left", va="center",
                fontweight="bold" if loc.id in route_nodes else "normal")

    for campus, (lon, lat) in {"BUSCH": (-74.4665, 40.5272), "LIVINGSTON": (-74.4395, 40.5272),
                               "COLLEGE AVENUE": (-74.4590, 40.4960),
                               "COOK / DOUGLASS": (-74.4470, 40.4820)}.items():
        ax.text(lon, lat, campus, fontsize=10, color="#9aa0aa", fontweight="bold")

    # Degrees of longitude are shorter than degrees of latitude at this
    # latitude; scale the axes so distances look right on the page.
    mid_lat = sum(l.lat for l in graph.locations.values()) / len(graph)
    ax.set_aspect(1 / math.cos(math.radians(mid_lat)))
    ax.set_xlabel("Longitude")
    ax.set_ylabel("Latitude")
    for side in ("top", "right"):
        ax.spines[side].set_visible(False)

    if route:
        start, end = graph.locations[route.path[0]], graph.locations[route.path[-1]]
        ax.set_title(f"Shortest route: {start.label} → {end.label}\n"
                     f"{route.meters:,.0f} m over {len(route.path) - 1} edges",
                     fontsize=12, fontweight="bold")

    legend = [Line2D([], [], marker=m, color=c, ls="", markersize=7, label=k.replace("_", " "))
              for k, (m, c) in CATEGORY_STYLE.items()]
    legend += [
        Line2D([], [], color="#b8bcc4", lw=0.9, label="nearby edge (≤ 600 m)"),
        Line2D([], [], color="#b8bcc4", lw=1.6, ls="--", label="campus connector"),
        Line2D([], [], color=ROUTE_COLOR, lw=3.5, label="shortest route"),
    ]
    ax.legend(handles=legend, loc="lower left", fontsize=8, frameon=False)

    fig.text(0.99, 0.01, "Coordinates © OpenStreetMap contributors (ODbL). "
             "Edge weights are straight-line distances.",
             ha="right", fontsize=7, color="#6b7280")

    out = Path(out_path)
    out.parent.mkdir(parents=True, exist_ok=True)
    fig.savefig(out, dpi=150, bbox_inches="tight")
    plt.close(fig)
    return out
