//go:build tools

// gomobile bind needs golang.org/x/mobile/bind in the bound module, but nothing in
// the package imports it, so this keeps "go mod tidy" from dropping it.
package tailnet

import _ "golang.org/x/mobile/bind"
