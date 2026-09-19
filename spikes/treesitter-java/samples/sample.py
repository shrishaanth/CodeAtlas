import os
import numpy as np, pandas
from . import utils
from ..core.hrp import allocate, rebalance as rb
from typing import (
    List,
    Optional,
)


class Portfolio(object):
    """A portfolio."""

    def __init__(self, weights: List[float]):
        self.weights = weights

    @property
    def total(self) -> float:
        return sum(self.weights)


async def fetch(url):
    import json  # local import
    return json.loads(url)


def main():
    try:
        import yaml
    except ImportError:
        yaml = None
    print(allocate(Portfolio([1.0]).total))
