import { useInRouterContext, useLocation, useNavigate } from 'react-router-dom'

/**
 * 라우터 안이면 useNavigate/useLocation을, 밖(라우터 없이 렌더한 단위 테스트 등)이면 브라우저 주소로 대신한다.
 * 한 컴포넌트 트리에서 라우터 유무는 바뀌지 않으므로 훅 호출 순서는 렌더마다 같다.
 */
export function useSafeRouter() {
  const inRouter = useInRouterContext()
  /* eslint-disable react-hooks/rules-of-hooks */
  const navigate = inRouter ? useNavigate() : null
  const location = inRouter ? useLocation() : null
  /* eslint-enable react-hooks/rules-of-hooks */
  return {
    navigate: navigate || ((to) => { if (typeof to === 'string') window.location.assign(to) }),
    location: location || { pathname: window.location.pathname, state: null },
  }
}
