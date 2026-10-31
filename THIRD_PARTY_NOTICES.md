# Third-party notices

## faultmanager-autoroot

The bundled `app/src/main/assets/webos/autoroot.sh` is from throwaway96's faultmanager-autoroot project: <https://github.com/throwaway96/faultmanager-autoroot>, pinned to commit `a972c95f4f4dbacdb72215409ea0c9eff39dabc1`. Its SHA-256 is `66a8a2816567a3c3d29768095873d347ec6bb2cbf60208979d15d7a42525d4d0`. The pinned script source and accompanying `app/src/main/assets/webos/COPYING` containing the GNU Affero General Public License version 3 or (at your option) any later version are included in this source tree.

The optional file picker accepts a replacement only when its contents match the same pinned SHA-256. This prevents the app from silently executing a different shell script under the autoroot label.
