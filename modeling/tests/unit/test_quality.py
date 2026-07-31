from __future__ import annotations

import pandas as pd
import pytest

from edutwin_modeling.data.quality import reject_forbidden_features, require_unique
from edutwin_modeling.errors import DataQualityError, LeakageError


def test_duplicate_keys_fail_quality_gate() -> None:
    frame = pd.DataFrame({"order_id": [1, 1], "skill_id": [10, 11]})
    with pytest.raises(DataQualityError):
        require_unique(frame, ["order_id"], "events")


def test_future_feature_names_fail_leakage_gate() -> None:
    with pytest.raises(LeakageError):
        reject_forbidden_features(["total_clicks", "final_result"], ["final_result"])
