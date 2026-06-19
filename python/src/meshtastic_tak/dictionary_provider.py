"""Loads the shipped zstd dictionaries and selects a dictionary per packet.

Two dictionaries ship in this package's ``resources`` directory: a ~512 KB
proto-trained non-aircraft dictionary (ID 0) and a ~4 KB aircraft dictionary
(ID 1). The dictionary ID rides in bits 0-5 of the wire flags byte. ID
``0xFF`` is reserved for the uncompressed (raw-protobuf) skip-compress path and
has no dictionary.

The dictionaries are static and shipped with the package — never adapted from
runtime traffic — which is what lets every packet decode independently from its
own bytes plus the dictionary (the LoRa-resilience invariant).
"""

from __future__ import annotations

import sys

if sys.version_info >= (3, 11):
    from importlib.resources import files
else:
    from importlib.resources import files  # type: ignore[attr-defined]
    # importlib.resources.files() is available from 3.9+ via importlib_resources
    # backport, but it was added to the stdlib in 3.9 as well (though the
    # Traversable API was refined in 3.11).  For 3.9/3.10 the stdlib version
    # works for our simple use-case (reading a file from a sub-package).

from .cot_type_mapper import CotTypeMapper, COTTYPE_OTHER

#: Wire dictionary ID for the non-aircraft (proto-trained) dictionary.
DICT_ID_NON_AIRCRAFT = 0
#: Wire dictionary ID for the aircraft dictionary.
DICT_ID_AIRCRAFT = 1
#: Flags-byte sentinel for an uncompressed raw-protobuf payload (no dictionary).
DICT_ID_UNCOMPRESSED = 0xFF


def _load_dict(name: str) -> bytes:
    """Read a shipped dictionary file from the package ``resources`` directory.

    Args:
        name: The dictionary filename, e.g. ``"dict_non_aircraft.zstd"``.

    Returns:
        The raw dictionary bytes.
    """
    return (
        files("meshtastic_tak.resources")
        .joinpath(name)
        .read_bytes()
    )


_non_aircraft_dict: bytes | None = None
_aircraft_dict: bytes | None = None


class DictionaryProvider:
    """Lazy loader and selector for the two shipped zstd dictionaries.

    Reads dictionaries from package resources on first use and memoizes the
    bytes in module-level caches, so the relatively large non-aircraft
    dictionary is loaded at most once per process. All methods are static.
    """

    @staticmethod
    def non_aircraft_dict() -> bytes:
        """Return the non-aircraft (proto-trained) dictionary bytes.

        Loaded from package resources on first call and cached thereafter.

        Returns:
            The raw non-aircraft dictionary bytes (dictionary ID 0).
        """
        global _non_aircraft_dict
        if _non_aircraft_dict is None:
            _non_aircraft_dict = _load_dict("dict_non_aircraft.zstd")
        return _non_aircraft_dict

    @staticmethod
    def aircraft_dict() -> bytes:
        """Return the aircraft dictionary bytes.

        Loaded from package resources on first call and cached thereafter.

        Returns:
            The raw aircraft dictionary bytes (dictionary ID 1).
        """
        global _aircraft_dict
        if _aircraft_dict is None:
            _aircraft_dict = _load_dict("dict_aircraft.zstd")
        return _aircraft_dict

    @staticmethod
    def get_dictionary(dict_id: int) -> bytes | None:
        """Return the dictionary bytes for a wire dictionary ID.

        Args:
            dict_id: A wire dictionary ID — :data:`DICT_ID_NON_AIRCRAFT` (0)
                or :data:`DICT_ID_AIRCRAFT` (1).

        Returns:
            The dictionary bytes for ``dict_id``, or ``None`` for any other ID
            (including :data:`DICT_ID_UNCOMPRESSED`, which has no dictionary).
        """
        if dict_id == DICT_ID_NON_AIRCRAFT:
            return DictionaryProvider.non_aircraft_dict()
        elif dict_id == DICT_ID_AIRCRAFT:
            return DictionaryProvider.aircraft_dict()
        return None

    @staticmethod
    def select_dict_id(cot_type_id: int, cot_type_str: str | None = None) -> int:
        """Choose the dictionary ID for a packet from its CoT type.

        Aircraft types use the aircraft dictionary; everything else uses the
        non-aircraft dictionary. When the type is unknown to the enum
        (:data:`COTTYPE_OTHER`), the classification falls back to the carried
        ``cot_type_str`` so forward-version aircraft packets still pick the
        aircraft dictionary.

        Args:
            cot_type_id: The packet's ``CotType`` enum tag.
            cot_type_str: The packet's carried CoT type string, used only when
                ``cot_type_id`` is :data:`COTTYPE_OTHER`.

        Returns:
            :data:`DICT_ID_AIRCRAFT` (1) for aircraft types, otherwise
            :data:`DICT_ID_NON_AIRCRAFT` (0).
        """
        if cot_type_id != COTTYPE_OTHER:
            return DICT_ID_AIRCRAFT if CotTypeMapper.is_aircraft(cot_type_id) else DICT_ID_NON_AIRCRAFT
        if cot_type_str and CotTypeMapper.is_aircraft_string(cot_type_str):
            return DICT_ID_AIRCRAFT
        return DICT_ID_NON_AIRCRAFT
