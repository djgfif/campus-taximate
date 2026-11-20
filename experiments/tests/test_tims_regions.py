from pathlib import Path
import sys
import unittest
sys.path.insert(0,str(Path(__file__).resolve().parents[1]))
import tims_regions as tr

class GridRegionTests(unittest.TestCase):
    def test_500m_grid_decode_and_center(self):
        self.assertEqual(tr.grid_center('가가000000'),(700250,1300250))
        self.assertEqual(tr.grid_center('다사137162'),(968750,1981250))
        self.assertEqual(tr.grid_center('다사199199'),(999750,1999750))
        for value in ('다사200001','다사000200','SCHOOL','다사0137','aa123123'):
            with self.assertRaises(ValueError):tr.grid_center(value)

    def test_polygon_not_nearest_point_and_hole_ambiguity(self):
        from shapely.geometry import Polygon
        a=Polygon([(0,0),(10,0),(10,10),(0,10)],holes=[[(4,4),(6,4),(6,6),(4,6)]])
        b=Polygon([(10,0),(20,0),(20,10),(10,10)])
        result=tr.spatial_assign([(1,1),(11,1),(100,100),(5,5),(10,5)],[('a',a),('b',b)])
        self.assertEqual(result,[('a','MATCHED'),('b','MATCHED'),('','UNMATCHED'),('','UNMATCHED'),('','AMBIGUOUS')])

if __name__=='__main__':unittest.main()
