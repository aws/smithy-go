package uri

import "strconv"

// ValidPortNumber returns whether the port is valid RFC 3986 port.
func ValidPortNumber(port string) bool {
	i, err := strconv.Atoi(port)
	if err != nil {
		return false
	}

	if i < 0 || i > 65535 {
		return false
	}
	return true
}

// ValidHostLabel returns whether the label is a valid RFC 3986 host label.
func ValidHostLabel(label string) bool {
	if l := len(label); l < 1 || l > 63 {
		return false
	}
	if c := label[0]; !isValidHostLabelFirstCharacter(rune(c)) {
		return false
	}

	for _, r := range label[1:] {
		switch {
		case r >= '0' && r <= '9':
		case r >= 'A' && r <= 'Z':
		case r >= 'a' && r <= 'z':
		case r == '-':
		default:
			return false
		}
	}

	return true
}

func isValidHostLabelFirstCharacter(r rune) bool {
	return (r >= 'a' && r <= 'z') || (r >= 'A' && r <= 'Z') || (r >= '0' && r <= '9')
}
